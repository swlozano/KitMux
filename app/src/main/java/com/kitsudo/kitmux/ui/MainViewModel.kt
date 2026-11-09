package com.kitsudo.kitmux.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kitsudo.kitmux.data.Folder
import com.kitsudo.kitmux.data.FolderStore
import com.kitsudo.kitmux.data.LibraryUtils
import com.kitsudo.kitmux.data.MusicScanner
import com.kitsudo.kitmux.data.Song
import com.kitsudo.kitmux.sync.SyncStatus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class FolderLayout { LIST, GRID }

/** Qué muestra la pantalla inicial (cuando no hay una carpeta abierta). */
enum class HomeMode {
    NORMAL,
    /** «Ocultar carpetas»: casillas para elegir cuáles ocultar. */
    SELECT_HIDDEN,
    /** «Ver carpetas ocultas»: lista de ocultas con «Mostrar». */
    SHOW_HIDDEN,
}

data class LibraryState(
    val songs: List<Song> = emptyList(),
    val folders: List<Folder> = emptyList(),
    /** Carpeta abierta (pantalla de canciones); null = pantalla inicial de carpetas. */
    val openFolder: String? = null,
    val folderLayout: FolderLayout = FolderLayout.LIST,
    val homeMode: HomeMode = HomeMode.NORMAL,
    /** Rutas de carpetas ocultas por el usuario (persistidas). */
    val hiddenFolders: Set<String> = emptySet(),
    /** Carpetas marcadas en el modo «Ocultar carpetas» (sin guardar todavía). */
    val selection: Set<String> = emptySet(),
    val scanning: Boolean = false,
    val scannedOnce: Boolean = false,
    val hasAudioPermission: Boolean = false,
) {
    val openFolderSongs: List<Song> =
        if (openFolder == null) emptyList() else songs.filter { it.folder == openFolder }

    /** id de canción -> carpeta, para saber qué carpeta está sonando. */
    val folderOfSong: Map<String, String> = songs.associate { it.id to it.folder }

    fun songsIn(folder: String): List<Song> = songs.filter { it.folder == folder }

    private val split = LibraryUtils.splitHidden(folders, hiddenFolders)
    val visibleFolders: List<Folder> = split.first
    val hiddenFolderList: List<Folder> = split.second
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = MusicScanner(app)
    private val folderStore = FolderStore(app)

    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        LibraryState(
            folderLayout = runCatching { FolderLayout.valueOf(uiPrefs.getString(KEY_LAYOUT, null)!!) }
                .getOrDefault(FolderLayout.LIST),
            hiddenFolders = uiPrefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet(),
        ),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        // Re-escanea cuando llegan archivos desde el PC (agrupando ráfagas de cambios).
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            SyncStatus.libraryChanges.debounce(1_500).collect { scan() }
        }
    }

    fun scan() {
        scanJob?.cancel()
        val hasPermission = hasAudioPermission()
        _state.update { it.copy(scanning = true, hasAudioPermission = hasPermission) }
        scanJob = viewModelScope.launch {
            val songs = withContext(Dispatchers.IO) {
                val fromMediaStore = if (hasPermission) runCatching { scanner.scanMediaStore() }.getOrDefault(emptyList()) else emptyList()
                val seen = fromMediaStore.mapTo(HashSet()) { LibraryUtils.dedupeKey(it.folder, it.displayName) }
                val fromFolders = folderStore.folders().flatMap { tree ->
                    runCatching { scanner.scanTree(tree, seen) }.getOrDefault(emptyList())
                        .filter { seen.add(LibraryUtils.dedupeKey(it.folder, it.displayName)) }
                }
                (fromMediaStore + fromFolders).sortedWith(
                    compareBy<Song>({ it.folder.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }),
                )
            }
            val folders = songs.groupingBy { it.folder }.eachCount()
                .map { (path, count) -> Folder(path, count) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            _state.update { current ->
                current.copy(
                    songs = songs,
                    folders = folders,
                    openFolder = current.openFolder?.takeIf { open -> folders.any { it.path == open } },
                    scanning = false,
                    scannedOnce = true,
                )
            }
        }
    }

    fun addFolder(treeUri: Uri) {
        folderStore.add(treeUri)
        scan()
    }

    fun openFolder(path: String) {
        _state.update { it.copy(openFolder = path) }
    }

    fun closeFolder() {
        _state.update { it.copy(openFolder = null) }
    }

    // --- Ocultar carpetas ---

    fun startHideSelection() {
        _state.update { it.copy(homeMode = HomeMode.SELECT_HIDDEN, selection = emptySet(), openFolder = null) }
    }

    fun toggleSelection(path: String) {
        _state.update { it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path) }
    }

    fun cancelHideSelection() {
        _state.update { it.copy(homeMode = HomeMode.NORMAL, selection = emptySet()) }
    }

    /** Guarda la selección como ocultas y devuelve las que se acaban de ocultar (para «Deshacer»). */
    fun saveHiddenSelection(): Set<String> {
        val current = _state.value
        val newlyHidden = current.selection - current.hiddenFolders
        setHidden(LibraryUtils.hide(current.hiddenFolders, current.selection))
        _state.update { it.copy(homeMode = HomeMode.NORMAL, selection = emptySet()) }
        return newlyHidden
    }

    fun showHiddenFolders() {
        _state.update { it.copy(homeMode = HomeMode.SHOW_HIDDEN, openFolder = null) }
    }

    fun exitHiddenFolders() {
        _state.update { it.copy(homeMode = HomeMode.NORMAL) }
    }

    fun hide(paths: Set<String>) {
        setHidden(LibraryUtils.hide(_state.value.hiddenFolders, paths))
    }

    fun unhide(paths: Set<String>) {
        setHidden(_state.value.hiddenFolders - paths)
    }

    private fun setHidden(hidden: Set<String>) {
        uiPrefs.edit { putStringSet(KEY_HIDDEN, hidden) }
        _state.update { it.copy(hiddenFolders = hidden) }
    }

    fun toggleFolderLayout() {
        val next = if (_state.value.folderLayout == FolderLayout.LIST) FolderLayout.GRID else FolderLayout.LIST
        uiPrefs.edit { putString(KEY_LAYOUT, next.name) }
        _state.update { it.copy(folderLayout = next) }
    }

    private companion object {
        const val KEY_LAYOUT = "folder_layout"
        const val KEY_HIDDEN = "hidden_folders"
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED
    }
}

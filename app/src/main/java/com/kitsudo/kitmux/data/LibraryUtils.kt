package com.kitsudo.kitmux.data

import java.text.Normalizer
import java.util.Locale

/** Funciones puras (sin dependencias de Android) para poder probarlas en la JVM. */
object LibraryUtils {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "mka", "amr", "mid", "midi",
    )

    fun isAudioFile(name: String, mimeType: String?): Boolean {
        if (mimeType != null && mimeType.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in AUDIO_EXTENSIONS
    }

    /** "/storage/emulated/0/Music/Rock/a.mp3" + raíz "/storage/emulated/0" -> "Music/Rock". */
    fun relativeFolderFromPath(filePath: String, storageRoot: String): String {
        val parent = filePath.substringBeforeLast('/', "")
        val root = storageRoot.trimEnd('/')
        return when {
            parent == root -> ""
            parent.startsWith("$root/") -> parent.removePrefix("$root/")
            else -> parent.trimStart('/')
        }
    }

    fun joinPath(parent: String, child: String): String =
        if (parent.isEmpty()) child else "$parent/$child"

    /** Clave para no duplicar un archivo que aparece tanto en MediaStore como en una carpeta añadida. */
    fun dedupeKey(folder: String, displayName: String): String =
        "${folder.trim('/')}/$displayName".lowercase(Locale.ROOT)

    fun folderDisplayName(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifEmpty { "Almacenamiento" }

    fun titleFromFileName(name: String): String = name.substringBeforeLast('.').ifEmpty { name }

    /** Carpetas visibles y ocultas, conservando el orden original. */
    fun splitHidden(folders: List<Folder>, hidden: Set<String>): Pair<List<Folder>, List<Folder>> =
        folders.partition { it.path !in hidden }

    /** Aplica el resultado de «Ocultar carpetas»: añade las seleccionadas a las ya ocultas. */
    fun hide(hidden: Set<String>, selection: Set<String>): Set<String> = hidden + selection

    private val DIACRITICS = Regex("\\p{Mn}+")

    /** Minúsculas y sin tildes: «Canción» -> «cancion». */
    fun normalizeForSearch(text: String): String =
        DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /**
     * Cada palabra de la búsqueda debe aparecer en alguno de los campos (sin importar mayúsculas,
     * tildes ni orden). Una búsqueda vacía no coincide con nada.
     */
    fun matchesSearch(query: String, fields: List<String?>): Boolean {
        val words = normalizeForSearch(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val haystack = fields.filterNotNull().joinToString("\n") { normalizeForSearch(it) }
        return words.all { it in haystack }
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}

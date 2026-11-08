package com.kitsudo.kitmux.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.color.MaterialColors
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.common.util.concurrent.ListenableFuture
import com.kitsudo.kitmux.R
import com.kitsudo.kitmux.data.Folder
import com.kitsudo.kitmux.data.LibraryUtils
import com.kitsudo.kitmux.data.Song
import com.kitsudo.kitmux.databinding.ActivityMainBinding
import com.kitsudo.kitmux.playback.PlaybackService
import com.kitsudo.kitmux.sync.SyncService
import com.kitsudo.kitmux.sync.SyncStatus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val songAdapter = SongAdapter(::onSongClicked)
    private val folderAdapter = FolderAdapter(onOpen = { viewModel.openFolder(it.path) }, onPlay = ::onFolderPlay)
    private var renderedLayout: FolderLayout? = null

    /** Con una carpeta abierta, «atrás» vuelve a la lista de carpetas. */
    private val closeFolderOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = viewModel.closeFolder()
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 500)
        }
    }
    private var userSeeking = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updatePlayerUi()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.scan() }

    private val syncPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startSyncIfAllowed() }

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                viewModel.addFolder(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()
        setupToolbar()
        setupPlayerControls()

        binding.songList.adapter = songAdapter
        binding.folderList.adapter = folderAdapter
        onBackPressedDispatcher.addCallback(this, closeFolderOnBack)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { SyncStatus.state.collect(::renderSync) }
            }
        }

        if (!viewModel.state.value.scannedOnce) {
            val missing = requiredPermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) viewModel.scan() else permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            updatePlayerUi()
        }, ContextCompat.getMainExecutor(this))
        progressHandler.post(progressUpdater)
    }

    override fun onStop() {
        progressHandler.removeCallbacks(progressUpdater)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    // region UI setup

    private fun applyInsets() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightNavigationBars = !night
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updatePadding(top = bars.top)
            binding.root.updatePadding(left = bars.left, right = bars.right)
            bottomInset = bars.bottom
            applyBottomInset()
            insets
        }
    }

    private var bottomInset = 0

    /** La barra de navegación queda bajo el reproductor si está visible; si no, bajo la lista. */
    private fun applyBottomInset() {
        val playerVisible = binding.miniPlayer.isVisible
        binding.miniPlayerContent.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.space_m) + bottomInset)
        val listBottom = resources.getDimensionPixelSize(R.dimen.space_s) + if (playerVisible) 0 else bottomInset
        binding.songList.updatePadding(bottom = listBottom)
        binding.folderList.updatePadding(bottom = listBottom)
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main)
        binding.toolbar.setNavigationOnClickListener { viewModel.closeFolder() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_layout -> {
                    viewModel.toggleFolderLayout()
                    true
                }
                R.id.action_rescan -> {
                    val missing = requiredPermissions().filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isEmpty()) viewModel.scan() else permissionLauncher.launch(missing.toTypedArray())
                    true
                }
                R.id.action_sync -> {
                    if (SyncStatus.state.value.enabled) SyncService.stop(this) else requestSync()
                    true
                }
                R.id.action_add_folder -> {
                    folderPicker.launch(null)
                    true
                }
                else -> false
            }
        }
    }

    // region Recepción desde PC

    private fun syncPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

    private fun requestSync() {
        val missing = syncPermissions()
        if (missing.isEmpty()) startSyncIfAllowed() else syncPermissionLauncher.launch(missing.toTypedArray())
    }

    /** La notificación es opcional; el permiso de escritura solo hace falta en Android 8–9. */
    private fun startSyncIfAllowed() {
        val canWrite = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (canWrite) {
            SyncService.start(this)
        } else {
            Toast.makeText(this, R.string.sync_needs_storage, Toast.LENGTH_LONG).show()
        }
    }

    private fun renderSync(state: SyncStatus.State) {
        binding.toolbar.menu.findItem(R.id.action_sync)?.isChecked = state.enabled
        binding.syncBanner.isVisible = state.enabled
        binding.syncText.text = when {
            state.address == null -> getString(R.string.sync_waiting_wifi)
            else -> buildString {
                append(getString(R.string.sync_banner_ready, state.pairingCode.orEmpty()))
                state.lastActivity?.let { append('\n').append(it) }
            }
        }
    }

    // endregion

    private fun setupPlayerControls() = with(binding) {
        syncStop.setOnClickListener { SyncService.stop(this@MainActivity) }
        playPause.setOnClickListener { togglePlayPause() }
        next.setOnClickListener { controller?.seekToNext() }
        previous.setOnClickListener { controller?.seekToPrevious() }
        shuffle.setOnClickListener { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
        repeat.setOnClickListener {
            controller?.let {
                it.repeatMode = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = LibraryUtils.formatDuration(progress.toLong())
            }
            override fun onStartTrackingTouch(bar: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                controller?.seekTo(bar.progress.toLong())
            }
        })
    }

    // endregion

    // region Library

    private fun render(state: LibraryState) {
        val home = state.openFolder == null
        closeFolderOnBack.isEnabled = !home
        binding.progress.isVisible = state.scanning
        renderToolbar(state, home)

        if (state.folderLayout != renderedLayout) applyFolderLayout(state.folderLayout)
        folderAdapter.submitList(state.folders)
        songAdapter.submitList(state.openFolderSongs)
        binding.folderList.isVisible = home
        binding.songList.isVisible = !home

        binding.emptyView.isVisible = home && state.folders.isEmpty()
        binding.emptyView.text = getString(
            when {
                state.scanning -> R.string.scanning
                !state.hasAudioPermission -> R.string.empty_no_permission
                else -> R.string.empty_no_music
            },
        )
        updatePlayerUi()
    }

    private fun renderToolbar(state: LibraryState, home: Boolean) = with(binding.toolbar) {
        binding.logoBlock.isVisible = home
        menu.findItem(R.id.action_layout)?.apply {
            isVisible = home
            val grid = state.folderLayout == FolderLayout.GRID
            setIcon(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view)
            title = getString(if (grid) R.string.view_as_list else R.string.view_as_grid)
        }
        if (home) {
            title = null
            subtitle = null
            navigationIcon = null
            val folders = resources.getQuantityString(R.plurals.folder_count, state.folders.size, state.folders.size)
            val songs = resources.getQuantityString(R.plurals.song_count, state.songs.size, state.songs.size)
            binding.songCount.isVisible = state.songs.isNotEmpty()
            binding.songCount.text = getString(R.string.library_summary, folders, songs)
        } else {
            val folder = state.folders.firstOrNull { it.path == state.openFolder }
            title = folder?.name
            subtitle = resources.getQuantityString(R.plurals.song_count, state.openFolderSongs.size, state.openFolderSongs.size)
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.back)
        }
    }

    private fun applyFolderLayout(layout: FolderLayout) {
        renderedLayout = layout
        folderAdapter.layout = layout
        binding.folderList.layoutManager = if (layout == FolderLayout.GRID) {
            val widthDp = resources.configuration.screenWidthDp
            val cellDp = resources.getDimension(R.dimen.grid_cell_min_width) / resources.displayMetrics.density
            GridLayoutManager(this, maxOf(2, (widthDp / cellDp).toInt()))
        } else {
            LinearLayoutManager(this)
        }
        val padding = if (layout == FolderLayout.GRID) resources.getDimensionPixelSize(R.dimen.space_s) else 0
        binding.folderList.updatePadding(left = padding, right = padding)
    }

    /** ▶ de una carpeta: la reproduce desde el principio, o pausa/reanuda si ya es la que suena. */
    private fun onFolderPlay(folder: Folder) {
        val c = controller ?: return
        val state = viewModel.state.value
        val current = c.currentMediaItem?.mediaId?.let(state.folderOfSong::get)
        if (current == folder.path) {
            togglePlayPause()
            return
        }
        val songs = state.songsIn(folder.path)
        if (songs.isEmpty()) return
        c.setMediaItems(songs.map { it.toMediaItem() }, 0, 0L)
        c.prepare()
        c.play()
    }

    private fun onSongClicked(position: Int) {
        val c = controller ?: return
        val songs = songAdapter.currentList
        c.setMediaItems(songs.map { it.toMediaItem() }, position, 0L)
        c.prepare()
        c.play()
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()

    // endregion

    // region Player UI

    private fun togglePlayPause() {
        val c = controller ?: return
        if (showsPause(c)) {
            c.pause()
        } else {
            when (c.playbackState) {
                Player.STATE_IDLE -> c.prepare()
                Player.STATE_ENDED -> c.seekToDefaultPosition()
                else -> Unit
            }
            c.play()
        }
    }

    private fun showsPause(c: Player): Boolean =
        c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE

    private fun updatePlayerUi() {
        val c = controller
        val item = c?.currentMediaItem
        if (binding.miniPlayer.isVisible != (item != null)) {
            binding.miniPlayer.isVisible = item != null
            applyBottomInset()
        }
        songAdapter.setCurrentSong(item?.mediaId)
        val playingFolder = item?.mediaId?.let(viewModel.state.value.folderOfSong::get)
        folderAdapter.setPlayback(playingFolder, c != null && showsPause(c))
        if (c == null || item == null) return

        val metadata = c.mediaMetadata
        binding.nowTitle.text = metadata.title ?: item.mediaMetadata.title
        binding.nowArtist.text = metadata.artist ?: item.mediaMetadata.artist ?: getString(R.string.unknown_artist)

        val pause = showsPause(c)
        binding.playPause.setImageResource(if (pause) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPause.contentDescription = getString(if (pause) R.string.pause else R.string.play)
        binding.shuffle.setToggleTint(c.shuffleModeEnabled)
        binding.repeat.setToggleTint(c.repeatMode != Player.REPEAT_MODE_OFF)
        binding.repeat.setImageResource(
            if (c.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
        )
        updateProgress()
    }

    /** Activo = color primario; inactivo = color secundario del texto. */
    private fun ImageButton.setToggleTint(active: Boolean) {
        val attr = if (active) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant
        ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(MaterialColors.getColor(this, attr)))
    }

    private fun updateProgress() {
        val c = controller ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        binding.seekBar.max = duration.toInt()
        binding.total.text = LibraryUtils.formatDuration(duration)
        if (!userSeeking) {
            binding.seekBar.progress = c.currentPosition.toInt()
            binding.elapsed.text = LibraryUtils.formatDuration(c.currentPosition)
        }
    }

    // endregion

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_AUDIO)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

}

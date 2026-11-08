package com.kitsudo.kitmux.data

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

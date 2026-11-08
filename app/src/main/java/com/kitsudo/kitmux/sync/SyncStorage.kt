package com.kitsudo.kitmux.sync

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class RemoteFile(val path: String, val size: Long)

/** Dónde se guardan los archivos recibidos: Música/KitMux/<ruta relativa>. */
interface SyncStorage {
    fun list(): List<RemoteFile>
    /** Escribe [size] bytes de [input] en [path], reemplazando el archivo si ya existe. */
    fun write(path: String, size: Long, input: InputStream)
    fun delete(path: String): Boolean
    fun move(from: String, to: String): Boolean

    companion object {
        fun create(context: Context): SyncStorage =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStoreSyncStorage(context)
            } else {
                LegacyFileSyncStorage(context)
            }

        internal const val TMP_PREFIX = "~kitmux-"

        private val FALLBACK_MIME = mapOf(
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
            "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/x-wav",
            "mka" to "audio/x-matroska", "amr" to "audio/amr", "mid" to "audio/midi", "midi" to "audio/midi",
        )

        fun mimeType(path: String): String {
            val ext = SyncPaths.extension(path)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.startsWith("audio/") }
                ?: FALLBACK_MIME[ext]
                ?: throw HttpException(415, "tipo de archivo no soportado: .$ext")
        }
    }
}

/** Android 10+: escribe a través de MediaStore; no necesita permisos para sus propios archivos. */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSyncStorage(context: Context) : SyncStorage {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val base = "${Environment.DIRECTORY_MUSIC}/${SyncPaths.FOLDER}/"

    override fun list(): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        val projection = arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        resolver.query(collection, projection, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$base%"), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val relative = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(SyncStorage.TMP_PREFIX)) continue
                    files += RemoteFile(relative.removePrefix(base) + name, c.getLong(2))
                }
            }
        return files
    }

    override fun write(path: String, size: Long, input: InputStream) {
        val name = SyncPaths.name(path)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, SyncStorage.TMP_PREFIX + UUID.randomUUID().toString().take(8) + "-" + name)
            put(MediaStore.MediaColumns.MIME_TYPE, SyncStorage.mimeType(path))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(path))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore rechazó el archivo")
        try {
            val written = resolver.openOutputStream(uri, "w")?.use { input.copyTo(it, 256 * 1024) }
                ?: throw IOException("no se pudo abrir el archivo")
            if (written != size) throw HttpException(400, "recibidos $written de $size bytes")
            find(path)?.let { resolver.delete(it, null, null) }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            val finalName = displayName(uri)
            if (finalName != name) {
                throw HttpException(415, "Android renombró el archivo a \"$finalName\"; tipo no soportado")
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    override fun delete(path: String): Boolean {
        val uri = find(path) ?: return false
        try {
            return resolver.delete(uri, null, null) > 0
        } catch (_: SecurityException) {
            throw HttpException(409, "el archivo no pertenece a KitMux (¿se reinstaló la app?)")
        }
    }

    override fun move(from: String, to: String): Boolean {
        val uri = find(from) ?: return false
        if (from == to) return true
        find(to)?.let { resolver.delete(it, null, null) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir(to))
            put(MediaStore.MediaColumns.DISPLAY_NAME, SyncPaths.name(to))
        }
        try {
            return resolver.update(uri, values, null, null) > 0
        } catch (_: SecurityException) {
            throw HttpException(409, "el archivo no pertenece a KitMux (¿se reinstaló la app?)")
        }
    }

    private fun relativeDir(path: String): String {
        val parent = SyncPaths.parent(path)
        return if (parent.isEmpty()) base else "$base$parent/"
    }

    private fun find(path: String): Uri? =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(relativeDir(path), SyncPaths.name(path)),
            null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
}

/** Android 8–9: archivos directos en /sdcard/Music/KitMux (requiere WRITE_EXTERNAL_STORAGE). */
class LegacyFileSyncStorage(context: Context) : SyncStorage {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    private val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SyncPaths.FOLDER)

    override fun list(): List<RemoteFile> =
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(SyncStorage.TMP_PREFIX) }
            .map { RemoteFile(it.relativeTo(root).invariantSeparatorsPath, it.length()) }
            .toList()

    override fun write(path: String, size: Long, input: InputStream) {
        SyncStorage.mimeType(path) // valida el tipo
        val dest = File(root, path)
        val dir = dest.parentFile ?: throw IOException("ruta inválida")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no se pudo crear $dir")
        val tmp = File(dir, SyncStorage.TMP_PREFIX + UUID.randomUUID().toString().take(8))
        try {
            val written = tmp.outputStream().use { input.copyTo(it, 256 * 1024) }
            if (written != size) throw HttpException(400, "recibidos $written de $size bytes")
            if (dest.exists() && !dest.delete()) throw IOException("no se pudo reemplazar $path")
            if (!tmp.renameTo(dest)) throw IOException("no se pudo guardar $path")
        } finally {
            tmp.delete()
        }
        scan(dest)
    }

    override fun delete(path: String): Boolean {
        val file = File(root, path)
        val deleted = file.isFile && file.delete()
        if (deleted) scan(file)
        return deleted
    }

    override fun move(from: String, to: String): Boolean {
        val src = File(root, from)
        if (!src.isFile) return false
        val dest = File(root, to)
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val moved = src.renameTo(dest)
        if (moved) scan(src, dest)
        return moved
    }

    private fun scan(vararg files: File) {
        MediaScannerConnection.scanFile(appContext, files.map { it.absolutePath }.toTypedArray(), null, null)
    }
}

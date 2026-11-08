package com.kitsudo.kitmux.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore

class MusicScanner(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** Todo el audio que el sistema ya tiene indexado en el almacenamiento (requiere permiso de audio). */
    fun scanMediaStore(): List<Song> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val usesRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        @Suppress("DEPRECATION")
        val pathColumn = if (usesRelativePath) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.AudioColumns.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            pathColumn,
        )
        @Suppress("DEPRECATION")
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath

        val songs = mutableListOf<Song>()
        resolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.DURATION)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(pathColumn)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val rawPath = c.getString(pathCol).orEmpty()
                val folder = if (usesRelativePath) {
                    rawPath.trim('/')
                } else {
                    LibraryUtils.relativeFolderFromPath(rawPath, storageRoot)
                }
                songs += Song(
                    id = "ms:$id",
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: LibraryUtils.titleFromFileName(name),
                    artist = c.getString(artistCol).cleanTag(),
                    album = c.getString(albumCol).cleanTag(),
                    durationMs = c.getLong(durationCol),
                    folder = folder,
                    displayName = name,
                )
            }
        }
        return songs
    }

    /**
     * Recorre recursivamente una carpeta elegida con el selector del sistema.
     * Los archivos cuya clave está en [skipKeys] (ya encontrados en MediaStore) se omiten
     * para no leer sus metadatos dos veces ni duplicarlos.
     */
    fun scanTree(treeUri: Uri, skipKeys: Set<String>): List<Song> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootPath = if (treeUri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            // docId con forma "primary:Music/Rock"
            rootId.substringAfter(':', "").trim('/')
        } else {
            queryDisplayName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)).orEmpty()
        }

        val songs = mutableListOf<Song>()
        val pending = ArrayDeque<Pair<String, String>>().apply { add(rootId to rootPath) }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        while (pending.isNotEmpty()) {
            val (parentId, parentPath) = pending.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = runCatching { resolver.query(childrenUri, columns, null, null, null) }.getOrNull()
            cursor?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(docId to LibraryUtils.joinPath(parentPath, name))
                        continue
                    }
                    if (!LibraryUtils.isAudioFile(name, mime)) continue
                    if (LibraryUtils.dedupeKey(parentPath, name) in skipKeys) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    songs += readSong(uri, name, parentPath)
                }
            }
        }
        return songs
    }

    private fun readSong(uri: Uri, name: String, folder: String): Song {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // Archivo dañado o formato no soportado por el extractor: usamos el nombre de archivo.
        } finally {
            runCatching { retriever.release() }
        }
        return Song(
            id = "saf:$uri",
            uri = uri,
            title = title?.takeIf { it.isNotBlank() } ?: LibraryUtils.titleFromFileName(name),
            artist = artist.cleanTag(),
            album = album.cleanTag(),
            durationMs = duration,
            folder = folder,
            displayName = name,
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun String?.cleanTag(): String? =
        this?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}

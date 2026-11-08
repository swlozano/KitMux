package com.kitsudo.kitmux.data

import android.net.Uri

data class Song(
    /** Estable entre escaneos: "ms:<id>" para MediaStore, "saf:<uri>" para carpetas añadidas. */
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    /** Ruta relativa de la carpeta, p. ej. "Music/Rock" ("" = raíz del almacenamiento). */
    val folder: String,
    val displayName: String,
)

data class Folder(val path: String, val songCount: Int) {
    val name: String get() = LibraryUtils.folderDisplayName(path)

    /** Carpeta que la contiene ("Music" para "Music/Rock"); vacío si está en la raíz. */
    val parentPath: String get() = path.trimEnd('/').substringBeforeLast('/', "")
}

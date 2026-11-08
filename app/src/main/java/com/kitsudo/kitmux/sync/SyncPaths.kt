package com.kitsudo.kitmux.sync

/** Validación de las rutas relativas que envía el PC (sin dependencias de Android). */
object SyncPaths {
    /** Carpeta dentro de Música donde se guardan los archivos sincronizados. */
    const val FOLDER = "KitMux"

    private const val MAX_DEPTH = 32
    private const val MAX_SEGMENT = 200
    private const val FORBIDDEN = "\\:*?\"<>|"

    /**
     * Devuelve la ruta normalizada ("Rock/a.mp3") o null si no es segura:
     * vacía, absoluta tras recortar, con "..", segmentos ocultos o caracteres no válidos.
     */
    fun normalize(raw: String): String? {
        if (raw.isBlank() || raw.length > 2048) return null
        val parts = raw.trim('/').split('/')
        if (parts.size > MAX_DEPTH) return null
        for (part in parts) {
            if (part.isEmpty() || part.startsWith(".") || part.length > MAX_SEGMENT) return null
            if (part.any { it < ' ' || it in FORBIDDEN }) return null
        }
        return parts.joinToString("/")
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    fun extension(path: String): String = name(path).substringAfterLast('.', "").lowercase()
}

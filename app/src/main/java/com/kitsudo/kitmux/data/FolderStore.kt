package com.kitsudo.kitmux.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** Guarda las carpetas elegidas con el selector del sistema (SAF) para re-escanearlas al abrir la app. */
class FolderStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("folders", Context.MODE_PRIVATE)

    /** Devuelve solo las carpetas cuyo permiso persistente sigue vigente. */
    fun folders(): List<Uri> {
        val granted = appContext.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }
            .toSet()
        val stored = prefs.getStringSet(KEY, emptySet()).orEmpty().map(Uri::parse)
        val valid = stored.filter { it in granted }
        if (valid.size != stored.size) save(valid)
        return valid
    }

    fun add(uri: Uri) = save(folders() + uri)

    private fun save(uris: Collection<Uri>) {
        prefs.edit { putStringSet(KEY, uris.map(Uri::toString).toSet()) }
    }

    private companion object {
        const val KEY = "tree_uris"
    }
}

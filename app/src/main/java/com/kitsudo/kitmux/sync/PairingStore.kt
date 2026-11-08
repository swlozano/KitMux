package com.kitsudo.kitmux.sync

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Identidad de este teléfono y las claves de los PCs emparejados (solo se guarda su hash). */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val deviceId: String = prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
        prefs.edit { putString(KEY_ID, it) }
    }

    /** Puerto usado la última vez; se reutiliza para que el PC lo encuentre aunque mDNS falle. */
    var lastPort: Int
        get() = prefs.getInt(KEY_PORT, 0)
        set(value) = prefs.edit { putInt(KEY_PORT, value) }

    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        val hashes = prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty() + sha256(token)
        prefs.edit { putStringSet(KEY_TOKENS, hashes) }
        return token
    }

    fun isValid(token: String): Boolean =
        sha256(token) in prefs.getStringSet(KEY_TOKENS, emptySet()).orEmpty()

    fun newPairingCode(): String = (random.nextInt(900_000) + 100_000).toString()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_TOKENS = "token_hashes"
        const val KEY_PORT = "last_port"
    }
}

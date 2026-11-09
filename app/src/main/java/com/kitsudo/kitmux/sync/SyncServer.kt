package com.kitsudo.kitmux.sync

import android.os.Build
import com.kitsudo.kitmux.data.LibraryUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/**
 * Protocolo de sincronización (JSON sobre HTTP):
 *  GET    /info              público: id y nombre del teléfono
 *  POST   /pair              {"code","client"} -> {"token"}
 *  GET    /manifest          [{"path","size"}] de Música/KitMux       (requiere token)
 *  PUT    /files/<ruta>      cuerpo = contenido del archivo            (requiere token)
 *  DELETE /files/<ruta>                                                (requiere token)
 *  POST   /move              {"from","to"}                             (requiere token)
 */
class SyncServer(
    private val storage: SyncStorage,
    private val pairing: PairingStore,
    private val onPairingCodeChanged: (String) -> Unit,
    private val onActivity: (String) -> Unit,
    /** Al empezar (started = true) y terminar, bien o mal, de recibir o borrar un archivo. */
    private val onTransfer: (SyncStatus.Activity, Boolean) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var pairingCode = pairing.newPairingCode()
    private var failedAttempts = 0

    val currentPairingCode: String get() = synchronized(lock) { pairingCode }

    fun handle(head: RequestHead, body: InputStream): HttpResponse {
        val path = head.path
        return when {
            path == "/info" -> expect(head, "GET") { info() }
            path == "/pair" -> expect(head, "POST") { pair(readJson(head, body)) }
            else -> {
                authorize(head)
                when {
                    path == "/manifest" -> expect(head, "GET") { manifest() }
                    path == "/move" -> expect(head, "POST") { move(readJson(head, body)) }
                    path.startsWith(FILES) -> fileRequest(head, body, path.removePrefix(FILES))
                    else -> throw HttpException(404, "ruta desconocida")
                }
            }
        }
    }

    private fun info() = json(
        JSONObject()
            .put("app", "kitmux")
            .put("version", PROTOCOL_VERSION)
            .put("id", pairing.deviceId)
            .put("name", deviceName()),
    )

    private fun pair(request: JSONObject): HttpResponse {
        val code = request.optString("code")
        val client = request.optString("client").take(64).ifBlank { "PC" }
        synchronized(lock) {
            val ok = MessageDigest.isEqual(code.toByteArray(), pairingCode.toByteArray())
            if (!ok) {
                failedAttempts++
                if (failedAttempts >= MAX_PAIR_ATTEMPTS) rotateCode()
                throw HttpException(403, "código incorrecto")
            }
            rotateCode() // un código sirve para un solo emparejado
        }
        onActivity("Emparejado con $client")
        return json(JSONObject().put("token", pairing.newToken()).put("id", pairing.deviceId).put("name", deviceName()))
    }

    private fun manifest(): HttpResponse {
        val files = JSONArray()
        storage.list().forEach { files.put(JSONObject().put("path", it.path).put("size", it.size)) }
        return json(JSONObject().put("files", files))
    }

    private fun fileRequest(head: RequestHead, body: InputStream, rawPath: String): HttpResponse {
        val path = SyncPaths.normalize(rawPath) ?: throw HttpException(400, "ruta no válida")
        return when (head.method) {
            "PUT" -> {
                if (!LibraryUtils.isAudioFile(SyncPaths.name(path), null)) throw HttpException(415, "solo se aceptan archivos de audio")
                val size = head.contentLength ?: throw HttpException(411, "se requiere Content-Length")
                onActivity("Recibiendo $path")
                tracked(SyncStatus.Activity.RECEIVING) { storage.write(path, size, body) }
                changed("Recibido $path")
            }
            "DELETE" -> {
                if (!tracked(SyncStatus.Activity.DELETING) { storage.delete(path) }) throw HttpException(404, "no existe")
                changed("Borrado $path")
            }
            else -> throw HttpException(405, "método no permitido")
        }
    }

    private fun move(request: JSONObject): HttpResponse {
        val from = SyncPaths.normalize(request.optString("from")) ?: throw HttpException(400, "origen no válido")
        val to = SyncPaths.normalize(request.optString("to")) ?: throw HttpException(400, "destino no válido")
        if (SyncPaths.extension(from) != SyncPaths.extension(to)) throw HttpException(400, "no se puede cambiar la extensión")
        if (!storage.move(from, to)) throw HttpException(404, "no existe")
        return changed("Movido $from → $to")
    }

    private inline fun <T> tracked(activity: SyncStatus.Activity, block: () -> T): T {
        onTransfer(activity, true)
        try {
            return block()
        } finally {
            onTransfer(activity, false)
        }
    }

    private fun changed(message: String): HttpResponse {
        onActivity(message)
        SyncStatus.notifyLibraryChanged()
        return json(JSONObject().put("ok", true))
    }

    private fun authorize(head: RequestHead) {
        val token = head.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrEmpty() || !pairing.isValid(token)) throw HttpException(401, "no emparejado")
    }

    private fun rotateCode() {
        pairingCode = pairing.newPairingCode()
        failedAttempts = 0
        onPairingCodeChanged(pairingCode)
    }

    private inline fun expect(head: RequestHead, method: String, block: () -> HttpResponse): HttpResponse {
        if (head.method != method) throw HttpException(405, "método no permitido")
        return block()
    }

    private fun readJson(head: RequestHead, body: InputStream): JSONObject {
        val length = head.contentLength ?: 0
        if (length > MAX_JSON_BYTES) throw HttpException(413, "petición demasiado grande")
        return try {
            JSONObject(body.readBytes().toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw HttpException(400, "JSON no válido")
        }
    }

    private fun json(obj: JSONObject) = HttpResponse(200, obj.toString().toByteArray())

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val FILES = "/files/"
        private const val MAX_PAIR_ATTEMPTS = 5
        private const val MAX_JSON_BYTES = 8 * 1024

        fun deviceName(): String = "KitMux ${Build.MODEL}"
    }
}

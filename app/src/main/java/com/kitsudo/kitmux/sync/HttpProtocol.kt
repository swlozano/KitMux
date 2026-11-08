package com.kitsudo.kitmux.sync

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException

/** Error que se traduce directamente en una respuesta HTTP con ese código. */
class HttpException(val status: Int, message: String) : IOException(message)

data class RequestHead(
    val method: String,
    /** Ruta ya decodificada (sin query), p. ej. "/files/Rock/a b.mp3". */
    val path: String,
    /** Cabeceras con el nombre en minúsculas. */
    val headers: Map<String, String>,
) {
    val contentLength: Long? get() = headers["content-length"]?.trim()?.toLongOrNull()
}

class HttpResponse(val status: Int, val body: ByteArray, val contentType: String = JSON) {
    companion object {
        const val JSON = "application/json; charset=utf-8"
    }
}

/** HTTP/1.1 mínimo: una petición por conexión, cuerpo con Content-Length (sin chunked). */
object HttpProtocol {
    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val END_OF_HEAD = 0x0D0A0D0A // \r\n\r\n

    /** Lee la línea de petición y las cabeceras. Devuelve null si la conexión se cerró sin datos. */
    fun readHead(input: InputStream): RequestHead? {
        val buffer = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (buffer.size() == 0) return null
                throw HttpException(400, "cabecera incompleta")
            }
            buffer.write(b)
            if (buffer.size() > MAX_HEAD_BYTES) throw HttpException(431, "cabecera demasiado grande")
            last4 = (last4 shl 8) or b
            if (last4 == END_OF_HEAD) break
        }
        val lines = buffer.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull()?.split(' ') ?: throw HttpException(400, "petición vacía")
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            throw HttpException(400, "línea de petición inválida")
        }
        val headers = lines.drop(1).associate { line ->
            val i = line.indexOf(':')
            if (i <= 0) throw HttpException(400, "cabecera inválida")
            line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            throw HttpException(411, "se requiere Content-Length")
        }
        return RequestHead(requestLine[0].uppercase(), decodePath(requestLine[1]), headers)
    }

    /** Decodifica los %XX (UTF-8) de la ruta. Un '+' se mantiene literal. */
    fun decodePath(target: String): String {
        val raw = target.substringBefore('?')
        if (!raw.startsWith("/")) throw HttpException(400, "ruta inválida")
        return try {
            URI(raw).path ?: throw HttpException(400, "ruta inválida")
        } catch (_: URISyntaxException) {
            throw HttpException(400, "ruta inválida")
        }
    }

    fun write(out: OutputStream, response: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        431 -> "Request Header Fields Too Large"
        else -> if (status >= 500) "Internal Server Error" else "Error"
    }
}

/** Expone exactamente [limit] bytes del flujo subyacente (el cuerpo de la petición). */
class LimitedInputStream(input: InputStream, limit: Long) : FilterInputStream(input) {
    var remaining: Long = limit
        private set

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining-- else throw HttpException(400, "cuerpo incompleto")
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw HttpException(400, "cuerpo incompleto")
        remaining -= n
        return n
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
    override fun markSupported() = false
    override fun close() {} // el socket lo cierra el servidor
}

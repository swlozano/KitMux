package com.kitsudo.kitmux.sync

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Servidor HTTP muy pequeño, suficiente para el protocolo de sincronización con el PC. */
class MiniHttpServer(
    private val handler: (RequestHead, InputStream) -> HttpResponse,
    private val onError: (Throwable) -> Unit = {},
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Escucha en todas las interfaces; devuelve el puerto asignado. */
    fun start(port: Int = 0): Int {
        check(serverSocket == null) { "ya iniciado" }
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        val workers = Executors.newFixedThreadPool(MAX_CONCURRENT)
        serverSocket = socket
        pool = workers
        thread(name = "kitmux-sync-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                workers.execute { serve(client) }
            }
        }
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        pool?.shutdownNow()
        serverSocket = null
        pool = null
    }

    private fun serve(socket: Socket): Unit = socket.use { s ->
        s.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        val output = BufferedOutputStream(s.getOutputStream())
        val response = try {
            val head = HttpProtocol.readHead(input) ?: return
            val body = LimitedInputStream(input, head.contentLength ?: 0L)
            handler(head, body)
        } catch (e: HttpException) {
            errorResponse(e.status, e.message)
        } catch (e: Exception) {
            onError(e)
            errorResponse(500, e.message ?: e.javaClass.simpleName)
        }
        try {
            HttpProtocol.write(output, response)
        } catch (_: IOException) {
            // el cliente cerró la conexión
        }
    }

    companion object {
        private const val MAX_CONCURRENT = 4
        private const val SOCKET_TIMEOUT_MS = 60_000

        fun errorResponse(status: Int, message: String?): HttpResponse {
            val escaped = (message ?: "error").replace("\\", "\\\\").replace("\"", "\\\"")
            return HttpResponse(status, "{\"error\":\"$escaped\"}".toByteArray())
        }
    }
}

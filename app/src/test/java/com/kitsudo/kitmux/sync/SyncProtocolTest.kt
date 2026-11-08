package com.kitsudo.kitmux.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class SyncProtocolTest {

    @Test
    fun normalize_acceptsSafeRelativePaths() {
        assertEquals("Rock/a b.mp3", SyncPaths.normalize("Rock/a b.mp3"))
        assertEquals("Rock/a.mp3", SyncPaths.normalize("/Rock/a.mp3/"))
        assertEquals("Canción ñ.flac", SyncPaths.normalize("Canción ñ.flac"))
    }

    @Test
    fun normalize_rejectsTraversalAndHidden() {
        listOf("", "../a.mp3", "Rock/../../a.mp3", "Rock//a.mp3", ".oculto/a.mp3", "a\\b.mp3", "a:b.mp3", "a\u0000.mp3")
            .forEach { assertNull("debería rechazar '$it'", SyncPaths.normalize(it)) }
    }

    @Test
    fun readHead_parsesRequestAndDecodesPath() {
        val raw = "PUT /files/Rock/Canci%C3%B3n%20+1.mp3?x=1 HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\nAuthorization: Bearer t\r\n\r\nhello"
        val input = ByteArrayInputStream(raw.toByteArray())
        val head = HttpProtocol.readHead(input)!!
        assertEquals("PUT", head.method)
        assertEquals("/files/Rock/Canción +1.mp3", head.path)
        assertEquals(5L, head.contentLength)
        assertEquals("Bearer t", head.headers["authorization"])
        assertEquals("hello", LimitedInputStream(input, 5).readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun readHead_rejectsGarbageAndChunked() {
        assertThrows(HttpException::class.java) { HttpProtocol.readHead(ByteArrayInputStream("hola\r\n\r\n".toByteArray())) }
        val chunked = "PUT /files/a.mp3 HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
        val e = assertThrows(HttpException::class.java) { HttpProtocol.readHead(ByteArrayInputStream(chunked.toByteArray())) }
        assertEquals(411, e.status)
        assertNull(HttpProtocol.readHead(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun limitedInputStream_stopsAtLimitAndDetectsTruncation() {
        assertEquals(3, LimitedInputStream(ByteArrayInputStream("abcdef".toByteArray()), 3).readBytes().size)
        assertThrows(HttpException::class.java) {
            LimitedInputStream(ByteArrayInputStream("ab".toByteArray()), 5).readBytes()
        }
    }
}

package com.kitsudo.kitmux.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUtilsTest {

    @Test
    fun isAudioFile_byMimeOrExtension() {
        assertTrue(LibraryUtils.isAudioFile("a.bin", "audio/mpeg"))
        assertTrue(LibraryUtils.isAudioFile("Canción.FLAC", null))
        assertTrue(LibraryUtils.isAudioFile("x.opus", "application/octet-stream"))
        assertFalse(LibraryUtils.isAudioFile("cover.jpg", "image/jpeg"))
        assertFalse(LibraryUtils.isAudioFile("README", null))
    }

    @Test
    fun relativeFolderFromPath() {
        val root = "/storage/emulated/0"
        assertEquals("Music/Rock", LibraryUtils.relativeFolderFromPath("$root/Music/Rock/a.mp3", root))
        assertEquals("", LibraryUtils.relativeFolderFromPath("$root/a.mp3", root))
        assertEquals("storage/1234-ABCD/Music", LibraryUtils.relativeFolderFromPath("/storage/1234-ABCD/Music/a.mp3", root))
    }

    @Test
    fun dedupeKey_matchesMediaStoreAndTreePaths() {
        assertEquals(
            LibraryUtils.dedupeKey("Music/Rock/", "Song.mp3"),
            LibraryUtils.dedupeKey(LibraryUtils.joinPath("Music", "Rock"), "song.MP3"),
        )
    }

    @Test
    fun folderDisplayName() {
        assertEquals("Rock", LibraryUtils.folderDisplayName("Music/Rock"))
        assertEquals("Almacenamiento", LibraryUtils.folderDisplayName(""))
    }

    @Test
    fun formatDuration() {
        assertEquals("0:00", LibraryUtils.formatDuration(0))
        assertEquals("3:05", LibraryUtils.formatDuration(185_000))
        assertEquals("1:01:01", LibraryUtils.formatDuration(3_661_000))
    }
}

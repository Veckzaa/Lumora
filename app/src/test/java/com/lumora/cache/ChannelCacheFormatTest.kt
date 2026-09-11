package com.lumora.cache

import com.lumora.model.Channel
import com.lumora.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

/**
 * The catalogue cache format, read back the way a cold start reads it (perf plan C1 replaced
 * split() with an index scan, so this pins what the scan must return).
 */
class ChannelCacheFormatTest {

    private val full = Channel(
        id = "x:1", name = "Film, The", url = "http://h/movie/u/p/1.mkv", logoUrl = "http://h/l.png",
        posterUrl = "http://h/p.jpg", backdropUrl = "http://h/b.jpg", group = "Action", tvgId = "tv.1",
        tvgName = "Film", tvgChno = "7", mediaType = MediaType.MOVIE, categoryId = "12",
        categoryName = "Action", description = "A plot", year = "2024", rating = "7.5",
        releaseDate = "2024-01-02", isJellyfin = true, streamUserAgent = "VLC", sourceProviderId = "p1",
        avOffsetMs = -120, stalkerCmd = "Y21k", tvArchive = true, tvArchiveDays = 7, tmdbId = "550",
        trailerKey = "abc", isPlex = true
    )
    private val sparse = Channel(name = "", url = "http://h/live/u/p/2.ts", mediaType = MediaType.SERIES)

    private fun write(vararg channels: Channel) = StringWriter().also { ChannelCache.write(it, channels.toList()) }.toString()
    private fun read(text: String) = ChannelCache.read(text.reader().buffered())

    @Test
    fun `channels round-trip, blank id falls back to url`() {
        val (channels, legacy) = read(write(full, sparse))
        assertEquals(listOf(full, sparse.copy(id = sparse.url)), channels)
        assertFalse(legacy)
    }

    @Test
    fun `separator and newlines inside a value are cleaned, not split`() {
        val dirty = full.copy(name = "ab\nc\rd")
        assertEquals(listOf(full.copy(name = "a b c d")), read(write(dirty)).first)
    }

    @Test
    fun `legacy 22-field line loads with defaults for the newer fields`() {
        val line = write(full).trimEnd('\n').split('').take(22).joinToString("")
        val (channels, legacy) = read(line + "\n")
        assertEquals(
            listOf(full.copy(stalkerCmd = "Y21k", tvArchive = false, tvArchiveDays = 0, tmdbId = null, trailerKey = null, isPlex = false)),
            channels
        )
        assertTrue(legacy)
    }

    @Test
    fun `blank and truncated lines are skipped`() {
        val short = write(full).split('').take(21).joinToString("")
        assertEquals(listOf(sparse.copy(id = sparse.url)), read("\n   \n$short\n" + write(sparse)).first)
    }

    @Test
    fun `unknown media type reads as live`() {
        val line = write(full).replace("MOVIE", "PODCAST")
        assertEquals(MediaType.LIVE, read(line).first.single().mediaType)
    }

    @Test
    fun `repeated category strings are shared, not copied per channel`() {
        val channels = read(write(full, full.copy(id = "x:2"))).first
        assertSame(channels[0].group, channels[1].group)
        assertSame(channels[0].group, channels[1].categoryName)
        assertSame(channels[0].year, channels[1].year)
    }
}

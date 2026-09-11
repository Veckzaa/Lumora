package com.lumora.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/** Live URL extension from the account's allowed_output_formats. */
class XtreamLiveContainerTest {

    @Test
    fun `HLS when the account allows it`() {
        assertEquals("m3u8", liveContainerFor(listOf("m3u8", "ts", "rtmp")))
    }

    @Test
    fun `TS when HLS output is switched off`() {
        assertEquals("ts", liveContainerFor(listOf("ts")))
    }

    @Test
    fun `TS when the panel sends no list`() {
        assertEquals("ts", liveContainerFor(emptyList()))
    }
}

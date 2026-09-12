package com.lumora.cache

import com.lumora.parser.XtreamClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the guide's in-memory programmes get fetched again. */
class EpgListStaleTest {

    private val nowMs = 1_789_300_000_000L
    private val minute = 60_000L

    /** A list whose last programme ends [hoursAhead] hours from now. */
    private fun endingIn(hoursAhead: Double) = listOf(
        XtreamClient.EpgProgram("x", nowMs / 1000 - 600, nowMs / 1000 + (hoursAhead * 3600).toLong())
    )

    @Test
    fun `a list fetched minutes ago is kept even if it covers little`() {
        assertFalse(epgListStale(endingIn(1.0), nowMs - 10 * minute, nowMs))
    }

    @Test
    fun `the 9 o'clock list at 14h with an hour left is fetched again`() {
        assertTrue(epgListStale(endingIn(1.0), nowMs - 5 * 60 * minute, nowMs))
    }

    @Test
    fun `an old list still covering the evening is kept`() {
        assertFalse(epgListStale(endingIn(6.0), nowMs - 5 * 60 * minute, nowMs))
    }

    @Test
    fun `a failed fetch is retried after half an hour, not on every scroll`() {
        assertFalse(epgListStale(emptyList(), nowMs - 10 * minute, nowMs))
        assertTrue(epgListStale(emptyList(), nowMs - 40 * minute, nowMs))
    }
}

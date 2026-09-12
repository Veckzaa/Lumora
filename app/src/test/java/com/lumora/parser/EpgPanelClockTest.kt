package com.lumora.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When a panel's guide gets shifted: local wall-clock stored as the UTC epoch, judged per channel. */
class EpgPanelClockTest {

    private val now = 1_789_300_000L
    private val paris = 2 * 3600L

    private fun show(fromMinutes: Long, toMinutes: Long) =
        XtreamClient.EpgProgram("x", now + fromMinutes * 60, now + toMinutes * 60)

    @Test
    fun `something airing as sent means the panel is right`() {
        assertEquals(false, epgLooksLocalAsUtc(listOf(show(-10, 20), show(20, 60)), paris, now))
    }

    @Test
    fun `listings only airing once moved back by the offset are local time stored as UTC`() {
        assertEquals(true, epgLooksLocalAsUtc(listOf(show(110, 150), show(150, 200)), paris, now))
    }

    @Test
    fun `an off-air channel back in five hours decides nothing`() {
        // The old guess shifted the whole provider by 5 h here.
        assertNull(epgLooksLocalAsUtc(listOf(show(300, 360), show(360, 420)), paris, now))
    }

    @Test
    fun `a panel on UTC never shifts`() {
        assertNull(epgLooksLocalAsUtc(listOf(show(110, 150)), 0L, now))
    }
}

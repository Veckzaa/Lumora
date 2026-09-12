package com.lumora.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Whether an update handed to the installer actually installed. */
class PendingInstallResultTest {

    private val handoffTime = 1_789_000_000_000L
    private val downloaded = 26_247_741L

    @Test
    fun `cancelled or failed install - nothing was installed since`() {
        assertNull(pendingInstallResult(handoffTime, handoffTime, downloaded, 26_000_000L))
    }

    @Test
    fun `the downloaded build was installed`() {
        assertEquals(true, pendingInstallResult(handoffTime, handoffTime + 60_000, downloaded, downloaded))
    }

    @Test
    fun `another build was sideloaded over it`() {
        assertEquals(false, pendingInstallResult(handoffTime, handoffTime + 60_000, downloaded, 27_757_586L))
    }
}

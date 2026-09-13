package com.lumora.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class LiveReconnectingDataSourceTest {

    /** One scripted connection: what opening it throws, or the bytes it sends and how it ends
     *  (null = the server closes it cleanly). */
    private class Connection(val bytes: ByteArray = ByteArray(0), val endsWith: IOException? = null, val openFails: IOException? = null)

    private class FakeUpstream(vararg connections: Connection, private val length: Long = C.LENGTH_UNSET.toLong()) : DataSource {
        private val pending = ArrayDeque(connections.toList())
        private var current: Connection? = null
        private var position = 0
        var opens = 0

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            opens++
            val connection = pending.removeFirstOrNull() ?: throw IOException("no connection scripted")
            connection.openFails?.let { throw it }
            current = connection
            position = 0
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val connection = checkNotNull(current) { "read on a closed source" }
            if (position == connection.bytes.size) {
                connection.endsWith?.let { throw it }
                return C.RESULT_END_OF_INPUT
            }
            val n = minOf(length, connection.bytes.size - position)
            System.arraycopy(connection.bytes, position, buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = null

        override fun close() {
            current = null
        }
    }

    // The source under test only hands its DataSpec on, and a real one needs an android.net.Uri,
    // which a JVM test can't build: allocate one without running its constructor.
    // Unsafe by name: the JVM has it, the android.jar tests compile against doesn't.
    private val spec: DataSpec = Class.forName("sun.misc.Unsafe").let { unsafe ->
        val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        unsafe.getMethod("allocateInstance", Class::class.java).invoke(instance, DataSpec::class.java) as DataSpec
    }

    private fun source(upstream: DataSource) = LiveReconnectingDataSource(upstream, sleep = {}, log = {})

    /** Reads up to [count] bytes, stopping early at end of input. */
    private fun DataSource.readBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(out, filled, count - filled)
            if (read == C.RESULT_END_OF_INPUT) return out.copyOf(filled)
            filled += read
        }
        return out
    }

    @Test
    fun `a dropped and then a closed connection carry on with the next bytes`() {
        val upstream = FakeUpstream(
            Connection(byteArrayOf(1, 2, 3), endsWith = IOException("Connection reset")),
            Connection(byteArrayOf(4, 5)),
            Connection(byteArrayOf(6)),
        )
        val live = source(upstream)
        assertEquals(C.LENGTH_UNSET.toLong(), live.open(spec))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), live.readBytes(6))
        assertEquals(3, upstream.opens)
    }

    @Test
    fun `a source with a length ends for real`() {
        val upstream = FakeUpstream(Connection(byteArrayOf(1, 2, 3)), length = 3)
        val file = source(upstream)
        file.open(spec)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes(10))
        assertEquals(1, upstream.opens)
    }

    @Test
    fun `the error goes up once every reconnect has failed`() {
        val refused = IOException("Response code: 403")
        val retries = LiveReconnectingDataSource.RECONNECT_DELAYS_MS.size
        val upstream = FakeUpstream(
            Connection(byteArrayOf(1), endsWith = IOException("Connection reset")),
            *Array(retries) { Connection(openFails = refused) },
        )
        val live = source(upstream)
        live.open(spec)
        try {
            live.readBytes(2)
            fail("expected the last reconnect's error")
        } catch (e: IOException) {
            assertSame(refused, e)
        }
        assertEquals(1 + retries, upstream.opens)
    }
}

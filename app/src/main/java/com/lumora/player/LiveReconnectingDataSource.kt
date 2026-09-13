package com.lumora.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Keeps an endless live stream (an Xtream, M3U or Stalker channel served as .ts over plain HTTP)
 * playing across a dropped or server-closed connection, by reopening the same URL underneath the
 * extractor.
 *
 * Media3 can't resume such a stream: it has no length and no seek map, so a load that fails
 * mid-stream is only retried once the buffer has played out, and then by resetting the whole
 * ProgressiveMediaPeriod and reloading from zero (configureRetry's deferred retry). Every drop was
 * a freeze plus 2.5-5 s of rebuffering, however soon the server would have answered again; on the
 * FireTV_API28 AVD a 2 s cut left RDS with no connection for 12 s. Reported on a stick as RDS
 * freezing every 10 minutes on a good connection: the provider cutting its connections. A clean
 * close was worse: the load simply ended, the player went to ENDED, and nothing restarts a live
 * channel from there. Reopened here at once, the extractor only sees a short pause in the bytes;
 * TsExtractor resyncs on the next packet and the buffer ahead keeps playing meanwhile.
 *
 * Only a source that opened without a length reconnects - one with a length really ended. After
 * [RECONNECT_DELAYS_MS] runs out with no real data in between, the error goes up as before, so
 * the retry ladder and version failover still handle a channel that is gone.
 */
internal class LiveReconnectingDataSource(
    private val upstream: DataSource,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit,
) : DataSource {

    private var dataSpec: DataSpec? = null
    private var endless = false
    private var attempts = 0
    private var bytesSinceReconnect = 0L

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        attempts = 0
        bytesSinceReconnect = 0
        val length = upstream.open(dataSpec)
        endless = length == C.LENGTH_UNSET.toLong()
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val cause: IOException? = try {
                val read = upstream.read(buffer, offset, length)
                if (read != C.RESULT_END_OF_INPUT || !endless) {
                    if (read > 0) countRead(read)
                    return read
                }
                null
            } catch (e: IOException) {
                // Interrupted is the loader cancelling (a channel change, the player released).
                if (!endless || e is InterruptedIOException) throw e
                e
            }
            reconnect(cause)
        }
    }

    private fun countRead(read: Int) {
        bytesSinceReconnect += read
        // Only real stream data earns the attempts back: a server that answers and closes at
        // once (an account over its connection limit) must still run out of them.
        if (bytesSinceReconnect >= RESET_AFTER_BYTES) attempts = 0
    }

    private fun reconnect(cause: IOException?) {
        var error = cause
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Live reconnect cancelled")
            if (attempts == RECONNECT_DELAYS_MS.size) {
                throw error ?: IOException("Live stream closed $attempts times in a row")
            }
            val delayMs = RECONNECT_DELAYS_MS[attempts++]
            log(
                "Live stream ${if (error == null) "closed by the server" else "dropped (${error.javaClass.simpleName}: ${error.message})"}" +
                    " - reconnecting, attempt $attempts of ${RECONNECT_DELAYS_MS.size}"
            )
            try {
                upstream.close()
            } catch (ignored: IOException) {
            }
            if (delayMs > 0) {
                try {
                    sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Live reconnect cancelled")
                }
            }
            try {
                upstream.open(checkNotNull(dataSpec))
                bytesSinceReconnect = 0
                return
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                error = e
            }
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    companion object {
        /** The wait before each reopen in a row. The first is immediate: a cut connection usually
         *  reopens at once, and a live stream only has a few seconds buffered ahead. */
        internal val RECONNECT_DELAYS_MS = longArrayOf(0, 250, 500, 1_000, 2_000)

        /** About a second of an HD stream - enough to say a reopen really worked. */
        private const val RESET_AFTER_BYTES = 512L * 1024
    }
}

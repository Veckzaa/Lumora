package com.lumora.util

import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Download speed to one URL: what Settings' speed tests and the player's "Test this channel"
 * measure. Blocking network reads - call it on Dispatchers.IO.
 *
 * Raw Mbit/s says little about a stream: servers send a live channel at its own bitrate, so a
 * gigabit line and a barely-coping one both read "5 Mbit/s". What matters is whether video arrives
 * as fast as it plays, so the stream's own bitrate is read from its bytes - the MPEG-TS clock (PCR)
 * or, for HLS, the segment durations - and [Result.speedRatio] compares the two.
 */
object SpeedTest {
    /** Cloudflare's speed-test endpoint: served from its nearest edge, no key. 50 MB is the most
     *  it serves (100 MB answers 403); a line past ~50 Mbps drains it before the 8 seconds are up,
     *  which still measures right since speed is bytes over elapsed time. */
    const val INTERNET_URL = "https://speed.cloudflare.com/__down?bytes=50000000"

    private const val PLAYLIST_LIMIT = 1L shl 20
    private const val TS_PACKET = 188
    private val BANDWIDTH = Regex("[:,]BANDWIDTH=(\\d+)")

    class Result(
        val mbps: Double,
        /** Request sent to response headers back: DNS, connect, TLS and the server's own delay. */
        val responseMs: Long,
        val bytes: Long,
        /** Host that served the stream after redirects - Xtream servers often hand streams to another box. */
        val host: String,
        /** The stream's own bitrate, null when its bytes don't say (a film file, a plain download). */
        val streamMbps: Double?,
        /** Body length the server announced, -1 when it didn't. */
        val contentLength: Long,
    )

    suspend fun run(
        client: OkHttpClient,
        url: String,
        userAgent: String?,
        headers: Map<String, String>?,
        durationMs: Long = 8_000,
        onProgress: (Double) -> Unit = {},
    ): Result {
        // Not through the app client's disk cache: it would store the test download and a second
        // run would read it back from disk.
        val http = client.newBuilder().cache(null).readTimeout(10, TimeUnit.SECONDS).build()
        fun request(u: HttpUrl): Response {
            val builder = Request.Builder().url(u)
            headers?.forEach { (k, v) -> builder.header(k, v) }
            if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
            val response = http.newCall(builder.build()).execute()
            if (!response.isSuccessful) { response.close(); throw IOException("HTTP ${response.code}") }
            return response
        }

        val first = url.toHttpUrlOrNull() ?: throw IOException("not an http(s) stream")
        val sent = System.nanoTime()
        val response = request(first)
        val responseMs = (System.nanoTime() - sent) / 1_000_000
        val host = response.request.url.host
        val contentLength = response.body?.contentLength() ?: -1L
        val durationNs = durationMs * 1_000_000
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        var start = System.nanoTime()
        var lastReport = start

        // False once the time is up.
        suspend fun drain(r: Response, clock: TsClock? = null): Boolean = r.use {
            val input = (it.body ?: return true).byteStream()
            var n = input.read(buffer)
            while (n >= 0) {
                clock?.feed(buffer, n)
                bytes += n
                val now = System.nanoTime()
                if (now - lastReport >= 500_000_000) { lastReport = now; onProgress(mbps(bytes, now - start)) }
                if (now - start >= durationNs) return false
                coroutineContext.ensureActive()
                n = input.read(buffer)
            }
            true
        }

        var streamMbps: Double? = null
        val isPlaylist = response.request.url.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
            response.body?.contentType()?.subtype?.contains("mpegurl", ignoreCase = true) == true
        if (!isPlaylist) {
            // Every stream byte goes through the clock, so it only runs on what looks like MPEG-TS;
            // a film file would otherwise be scanned byte by byte for nothing.
            val body = response.body
            val isTs = body != null && body.source().request(TS_PACKET + 1L) &&
                body.source().buffer[0] == SYNC && body.source().buffer[TS_PACKET.toLong()] == SYNC
            val clock = if (isTs) TsClock() else null
            drain(response, clock)
            // Under a second of clock is too little to call a bitrate.
            if (clock != null && clock.seconds >= 1.0) streamMbps = bytes * 8 / 1e6 / clock.seconds
        } else {
            var playlistUrl = response.request.url
            var text = response.use { it.peekBody(PLAYLIST_LIMIT).string() }
            var declared: Double? = null
            bestVariant(text, playlistUrl)?.let { (variant, bandwidth) ->
                declared = bandwidth / 1e6
                request(variant).use { playlistUrl = it.request.url; text = it.peekBody(PLAYLIST_LIMIT).string() }
            }
            val segments = segments(text, playlistUrl)
            if (segments.isEmpty()) throw IOException("empty playlist")
            // Clock restarts here so the playlist round trips don't count as slow download.
            start = System.nanoTime()
            lastReport = start
            var doneBytes = 0L
            var doneSeconds = 0.0
            for ((segment, seconds) in segments) {
                val before = bytes
                if (!drain(request(segment))) break
                doneBytes += bytes - before
                doneSeconds += seconds
            }
            // Whole segments against their EXTINF time; the variant's declared (peak) BANDWIDTH
            // only when not even one segment finished in time.
            streamMbps = if (doneSeconds > 0) doneBytes * 8 / 1e6 / doneSeconds else declared
        }
        return Result(mbps(bytes, System.nanoTime() - start), responseMs, bytes, host, streamMbps, contentLength)
    }

    private const val SYNC = 0x47.toByte()

    private fun mbps(bytes: Long, ns: Long): Double = bytes * 8 / 1e6 / (ns.coerceAtLeast(1) / 1e9)

    /** Seconds of video in an MPEG-TS byte stream, from the PCR clock of the first PID that carries one.
     *  A jump back or of more than 2 s (an ad splice, a wrap) starts a new run instead of counting the gap. */
    internal class TsClock {
        private val packet = ByteArray(TS_PACKET)
        private var filled = 0
        private var pcrPid = -1
        private var runStart = -1L
        private var last = -1L
        private var earlierRuns = 0L

        val seconds: Double get() = if (last < 0) 0.0 else (earlierRuns + last - runStart) / 90_000.0

        fun feed(data: ByteArray, length: Int) {
            var i = 0
            while (i < length) {
                // Out of step: skip to the next sync byte.
                if (filled == 0 && data[i] != SYNC) { i++; continue }
                val n = minOf(TS_PACKET - filled, length - i)
                System.arraycopy(data, i, packet, filled, n)
                filled += n
                i += n
                if (filled == TS_PACKET) { onPacket(); filled = 0 }
            }
        }

        private fun onPacket() {
            val adaptation = ((packet[3].toInt() shr 4) and 3) >= 2
            if (!adaptation || (packet[4].toInt() and 0xFF) < 7 || (packet[5].toInt() and 0x10) == 0) return
            val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
            if (pcrPid == -1) pcrPid = pid else if (pid != pcrPid) return
            val pcr = ((packet[6].toLong() and 0xFF) shl 25) or ((packet[7].toLong() and 0xFF) shl 17) or
                ((packet[8].toLong() and 0xFF) shl 9) or ((packet[9].toLong() and 0xFF) shl 1) or
                ((packet[10].toLong() and 0xFF) shr 7)
            if (last < 0) runStart = pcr
            else if (pcr < last || pcr - last > 2 * 90_000) { earlierRuns += last - runStart; runStart = pcr }
            last = pcr
        }
    }

    /** Highest-BANDWIDTH variant of a master playlist and that bandwidth; null for a media playlist. */
    internal fun bestVariant(text: String, base: HttpUrl): Pair<HttpUrl, Long>? {
        var best: Pair<HttpUrl, Long>? = null
        var pending: Long? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> pending = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                pending != null && line.isNotEmpty() && !line.startsWith("#") -> {
                    val url = base.resolve(line)
                    if (url != null && (best == null || pending > best.second)) best = url to pending
                    pending = null
                }
            }
        }
        return best
    }

    /** Segment URLs of a media playlist, in order, with their EXTINF seconds (0 when missing). */
    internal fun segments(text: String, base: HttpUrl): List<Pair<HttpUrl, Double>> {
        val out = ArrayList<Pair<HttpUrl, Double>>()
        var seconds = 0.0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> seconds = line.substring(8).substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> { base.resolve(line)?.let { out.add(it to seconds) }; seconds = 0.0 }
            }
        }
        return out
    }
}

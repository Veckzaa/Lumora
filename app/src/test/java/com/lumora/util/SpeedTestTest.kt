package com.lumora.util

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class SpeedTestTest {

    private fun serve(routes: Map<String, MockResponse>): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = routes[request.path] ?: MockResponse().setResponseCode(404)
        }
    }

    private fun bytes(n: Int) = MockResponse().setBody(Buffer().write(ByteArray(n)))

    /** One MPEG-TS packet on PID 0x100, carrying [pcr] (90 kHz units) when given. */
    private fun tsPacket(pcr: Long? = null) = ByteArray(188).also { p ->
        p[0] = 0x47; p[1] = 0x01
        if (pcr == null) { p[3] = 0x10; return@also }
        p[3] = 0x30; p[4] = 7; p[5] = 0x10
        p[6] = (pcr shr 25).toByte(); p[7] = (pcr shr 17).toByte(); p[8] = (pcr shr 9).toByte()
        p[9] = (pcr shr 1).toByte(); p[10] = ((pcr and 1) shl 7).toByte()
    }

    @Test
    fun `a live MPEG-TS stream's bitrate comes from its PCR clock`() = runBlocking {
        // A PCR every half second (real streams carry one at least every 100 ms; a gap over 2 s
        // reads as a splice), 0 to 4 s across 1001 packets.
        val ts = Buffer()
        for (i in 0..1000) ts.write(if (i % 125 == 0) tsPacket(i / 125 * 45_000L) else tsPacket())
        serve(mapOf("/live/1.ts" to MockResponse().setBody(ts))).use { server ->
            val result = SpeedTest.run(OkHttpClient(), server.url("/live/1.ts").toString(), null, null)
            // 1001 packets over 4 seconds of clock.
            assertEquals(1001 * 188 * 8 / 1e6 / 4, result.streamMbps!!, 1e-9)
        }
    }

    @Test
    fun `an HLS master is followed to its best variant, bitrate from segment durations`() = runBlocking {
        serve(mapOf(
            "/live/master.m3u8" to MockResponse().setBody(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n" +
                    "#EXT-X-STREAM-INF:RESOLUTION=1920x1080,BANDWIDTH=5000000\nhigh/index.m3u8\n"
            ),
            "/live/high/index.m3u8" to MockResponse().setBody("#EXTM3U\n#EXTINF:6.0,\nseg1.ts\n#EXTINF:6,\n/live/high/seg2.ts\n"),
            "/live/high/seg1.ts" to bytes(100_000),
            "/live/high/seg2.ts" to bytes(50_000),
        )).use { server ->
            val result = SpeedTest.run(OkHttpClient(), server.url("/live/master.m3u8").toString(), null, null)
            assertEquals(150_000L, result.bytes)
            assertEquals(150_000 * 8 / 1e6 / 12, result.streamMbps!!, 1e-9)
        }
    }

    @Test
    fun `a plain file is read to its end with no bitrate of its own`() = runBlocking {
        serve(mapOf("/movie.mkv" to bytes(300_000))).use { server ->
            val result = SpeedTest.run(OkHttpClient(), server.url("/movie.mkv").toString(), null, null)
            assertEquals(300_000L, result.bytes)
            assertEquals(300_000L, result.contentLength)
            assertNull(result.streamMbps)
        }
    }

    @Test(expected = IOException::class)
    fun `an HTTP error fails the test`() = runBlocking<Unit> {
        serve(emptyMap()).use { server -> SpeedTest.run(OkHttpClient(), server.url("/gone.ts").toString(), null, null) }
    }
}

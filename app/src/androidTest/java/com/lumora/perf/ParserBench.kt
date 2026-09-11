package com.lumora.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.data.parser.XmltvParser
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.parser.M3uParser
import com.lumora.parser.XtreamClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.source
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** B3-B5: turning provider payloads into Channels / programmes. Inputs are files in cacheDir,
 *  generated once per test outside the timed region. */
@RunWith(AndroidJUnit4::class)
class ParserBench {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun m3uParse50k() {
        val file = File(context.cacheDir, "bench.m3u").also { Fixtures.writeM3u(it) }
        Bench.run("B3 m3u parse 50k", prepare = { }) {
            val result = file.bufferedReader().use { M3uParser.parse(it) }
            check(result.channels.size == Fixtures.CATALOGUE) { "parsed ${result.channels.size}" }
        }
    }

    /** Through the real fetch path (OkHttp body -> JSON -> Channel) against a local server, so
     *  the body-to-String and org.json costs are part of the number. */
    @Test
    fun xtreamLive30k() {
        val file = File(context.cacheDir, "bench-xtream-live.json").also { Fixtures.writeXtreamLive(it) }
        val server = MockWebServer().apply { start() }
        try {
            val provider = Provider(
                name = "bench",
                type = ProviderType.XTREAM,
                serverUrl = server.url("/").toString().trimEnd('/'),
                username = "u",
                password = "p"
            )
            // A plain client: the app's shared one has a disk cache that would serve later runs from disk.
            val client = XtreamClient(OkHttpClient())
            Bench.run("B4 xtream live parse 30k", prepare = {
                server.enqueue(MockResponse().setBody(Buffer().apply { file.source().use { writeAll(it) } }))
            }) {
                val live = runBlocking { client.getLiveStreams(provider, "ts") }
                check(live.size == Fixtures.LIVE) { "parsed ${live.size}" }
            }
        } finally {
            server.shutdown()
        }
    }

    /** The parser the app uses: native when the library loaded, Kotlin otherwise. */
    @Test
    fun xmltvParse50k() {
        val file = File(context.cacheDir, "bench.xml").also { Fixtures.writeXmltv(it) }
        Bench.run("B5 xmltv parse 50k", prepare = { }) {
            val result = file.inputStream().buffered().use { XmltvParser.parse(it) }
            check(result.programmes.size == Fixtures.EPG_PROGRAMMES) { "parsed ${result.programmes.size}" }
        }
    }

    /** The Kotlin parser on its own, for the before/after next to B5 while both copies exist. */
    @Test
    fun xmltvParse50kKotlin() {
        val file = File(context.cacheDir, "bench.xml").also { Fixtures.writeXmltv(it) }
        Bench.run("B5k xmltv parse 50k kotlin", prepare = { }) {
            val result = file.inputStream().buffered().use { XmltvParser.parse(it, native = false) }
            check(result.programmes.size == Fixtures.EPG_PROGRAMMES) { "parsed ${result.programmes.size}" }
        }
    }
}

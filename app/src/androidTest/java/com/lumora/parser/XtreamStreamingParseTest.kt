package com.lumora.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.model.Provider
import com.lumora.model.ProviderType
import com.lumora.util.normalizeServerUrl
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The streamed Xtream list parse (perf plan C3) must produce exactly the channels the org.json
 * version did, quirks included: JSON null read as the text "null", numbers and booleans as their
 * text, the last duplicate key winning, non-object entries skipped, and a bad body giving no
 * channels at all. Instrumented because android.util.JsonReader only exists on a device.
 */
@RunWith(AndroidJUnit4::class)
class XtreamStreamingParseTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: Provider
    private lateinit var base: String
    private val client = XtreamClient(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val url = server.url("/").toString().trimEnd('/')
        provider = Provider(name = "t", type = ProviderType.XTREAM, serverUrl = url, username = "u", password = "p")
        base = normalizeServerUrl(url)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun liveBareArrayWithPanelQuirks() {
        respond(
            """
            [
              {"num":1,"name":"A","stream_id":101,"stream_icon":"http://i/a.png","category_id":"5","category_name":"News","tv_archive":1,"tv_archive_duration":"7","rating":"","year":null},
              {"name":"no id"},
              "not an object",
              {"name":null,"stream_id":"102","category_id":null,"category_ids":[null,"",9,10],"tv_archive":"1","tv_archive_duration":0,"container_extension":"m3u8","category_name":null,"extra":{"deep":[1,{"x":true}]}},
              {"stream_id":103,"name":"Dup","name":"Dup last","tv_archive":true,"tv_archive_duration":3,"category_name":"News"}
            ]
            """
        )
        val live = runBlocking { client.getLiveStreams(provider, "ts") }
        assertEquals(
            listOf(
                Channel(
                    id = "101", name = "A", url = "$base/live/u/p/101.ts", logoUrl = "http://i/a.png",
                    categoryId = "5", categoryName = "News", mediaType = MediaType.LIVE, year = "null",
                    tvArchive = true, tvArchiveDays = 7
                ),
                Channel(
                    id = "102", name = "null", url = "$base/live/u/p/102.m3u8", categoryId = "9",
                    categoryName = "", mediaType = MediaType.LIVE
                ),
                Channel(
                    id = "103", name = "Dup last", url = "$base/live/u/p/103.ts", categoryId = "",
                    categoryName = "News", mediaType = MediaType.LIVE, tvArchive = true, tvArchiveDays = 3
                ),
            ),
            live
        )
        assertSame(live[0].categoryName, live[2].categoryName)
    }

    @Test
    fun vodObjectPrefersItsOwnKeyOverItems() {
        respond(
            """{"items":[{"stream_id":8}],"vod_streams":[{"stream_id":7,"name":"Film","stream_icon":"http://i/f.jpg","container_extension":"mkv","rating":7.5,"year":"2024","tmdb":"0","trailer":"https://youtu.be/abc123?t=1"},{"stream_id":9,"name":"Plain","tmdb":550,"stream_icon":null}]}"""
        )
        assertEquals(
            listOf(
                Channel(
                    id = "7", name = "Film", url = "$base/movie/u/p/7.mkv", logoUrl = "http://i/f.jpg",
                    posterUrl = "http://i/f.jpg", categoryId = "", categoryName = "", mediaType = MediaType.MOVIE,
                    rating = "7.5", year = "2024", trailerKey = "abc123"
                ),
                Channel(
                    id = "9", name = "Plain", url = "$base/movie/u/p/9.mp4", categoryId = "", categoryName = "",
                    mediaType = MediaType.MOVIE, tmdbId = "550"
                ),
            ),
            runBlocking { client.getVodStreams(provider) }
        )
    }

    @Test
    fun seriesFromItemsWrapper() {
        respond(
            """{"items":[{"series_id":"s1","name":"Show","cover":"http://i/s.jpg","release_date":"2020-01-01","youtube_trailer":"","trailer":"xyz","tmdb":123,"category_ids":[4]}]}"""
        )
        assertEquals(
            listOf(
                Channel(
                    id = "s1", name = "Show", url = "", logoUrl = "http://i/s.jpg", posterUrl = "http://i/s.jpg",
                    categoryId = "4", categoryName = "", mediaType = MediaType.SERIES, releaseDate = "2020-01-01",
                    tmdbId = "123", trailerKey = "xyz"
                )
            ),
            runBlocking { client.getSeries(provider) }
        )
    }

    @Test
    fun badResponsesGiveNoChannels() {
        for (body in listOf("", "   \n", """[{"stream_id":1,"name":"cut""", """[{"stream_id":1}""", "\"just a string\"", "<html>502</html>")) {
            respond(body)
            assertEquals(body, emptyList<Channel>(), runBlocking { client.getLiveStreams(provider, "ts") })
        }
        respond("""[{"stream_id":1}]""", code = 500)
        assertEquals(emptyList<Channel>(), runBlocking { client.getLiveStreams(provider, "ts") })
    }
}

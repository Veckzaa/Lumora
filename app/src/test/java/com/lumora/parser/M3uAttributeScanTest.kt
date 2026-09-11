package com.lumora.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hand-written EXTINF attribute scanner must extract exactly what the regex it replaced did
 * (perf plan C4). The old regex is kept here as the oracle, fed the malformed lines real
 * playlists contain.
 */
class M3uAttributeScanTest {

    private val oldRegex = """(\w[\w-]*)\s*=\s*"([^"]*)"""".toRegex()

    private val lines = listOf(
        """-1 tvg-id="cnn.us" tvg-name="CNN" tvg-logo="http://x/l.png" group-title="News",CNN""",
        """-1 tvg-id = "spaced"  group-title=  "Sp ace",Title""",
        """-1 TVG-ID="upper" Group-Title="Mixed",Title""",
        """-1 -tvg-id="leading dash" --group-title="x",Title""",
        """-1 tvg-name="a=b, c" tvg-logo="" group-title="Films, Drama",Title""",
        """-1 tvg-name="unclosed group-title="G",Title""",
        """-1 bad=unquoted tvg-chno=7 tvg-id="ok" year="2024" rating="7.5",Title""",
        """-1 a="1"tvg-id="adjacent" b= ="skip",Title""",
        """-1 tvg-id="first" tvg-id="last wins",Title""",
        """-1 group-title="A",Show tvg-name="inside the title"""",
        """-1 tvg-id="x" group-title="dangling""",
        "-1,No attributes",
    )

    @Test
    fun `scanner extracts the same attributes as the old regex`() {
        for (line in lines) {
            val expected = HashMap<String, String>()
            oldRegex.findAll(line).take(50).forEach { expected[it.groupValues[1].lowercase()] = it.groupValues[2] }

            val ch = M3uParser.parse("#EXTM3U\n#EXTINF:$line\nhttps://ex.com/1.ts\n").channels.single()
            assertEquals(line, expected["tvg-id"], ch.tvgId)
            assertEquals(line, expected["tvg-name"], ch.tvgName)
            assertEquals(line, expected["tvg-logo"]?.ifBlank { null }, ch.logoUrl)
            assertEquals(line, expected["tvg-chno"], ch.tvgChno)
            assertEquals(line, expected["group-title"], ch.group)
            assertEquals(line, expected["year"], ch.year)
            assertEquals(line, expected["rating"], ch.rating)
        }
    }
}

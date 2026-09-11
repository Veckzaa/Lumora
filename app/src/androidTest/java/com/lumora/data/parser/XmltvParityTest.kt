package com.lumora.data.parser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumora.nativelib.LumoraNative
import com.lumora.perf.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * The Rust XMLTV parser against the Kotlin one, on the same input, for every field the EPG
 * sync reads. Instrumented because the `.so` only exists for Android ABIs. Runs on the
 * benchmark build type like the perf benchmarks (`testBuildType`).
 */
@RunWith(AndroidJUnit4::class)
class XmltvParityTest {

    private fun both(xml: String): Pair<XmltvParser.XmltvResult, XmltvParser.XmltvResult> {
        assertTrue("native library did not load", LumoraNative.available)
        val native = XmltvParser.parse(xml.byteInputStream(), native = true)
        val kotlin = XmltvParser.parse(xml.byteInputStream(), native = false)
        return native to kotlin
    }

    private fun assertSame(native: XmltvParser.XmltvResult, kotlin: XmltvParser.XmltvResult) {
        assertEquals(kotlin.channels.map { it.id to it.displayName }, native.channels.map { it.id to it.displayName })
        assertEquals(kotlin.programmes.size, native.programmes.size)
        for (i in kotlin.programmes.indices) {
            val k = kotlin.programmes[i]
            val n = native.programmes[i]
            assertEquals("programme $i", listOf(k.channel, k.title, k.startTimestamp, k.stopTimestamp), listOf(n.channel, n.title, n.startTimestamp, n.stopTimestamp))
        }
    }

    @Test
    fun benchmarkFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "parity.xml").also { Fixtures.writeXmltv(it) }
        assertTrue(LumoraNative.available)
        val native = file.inputStream().buffered().use { XmltvParser.parse(it, native = true) }
        val kotlin = file.inputStream().buffered().use { XmltvParser.parse(it, native = false) }
        assertEquals(Fixtures.EPG_PROGRAMMES, kotlin.programmes.size)
        assertSame(native, kotlin)
    }

    @Test
    fun awkwardFeed() {
        val (native, kotlin) = both(
            """<?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="a.tv"><display-name lang="fr">Caf&#233; &amp; Th&eacute;</display-name><icon src="x"/></channel>
              <channel id="b.tv"><display-name>First</display-name><display-name>Second</display-name></channel>
              <channel id="c.tv"/>
              <programme start="20260910220000 +0000" stop="20260910230000 +0130" channel="a.tv">
                <title lang="en">Quotes "here" &lt;tag&gt;</title><desc>d</desc>
                <credits><actor>A</actor><director>D</director></credits><star-rating><value>7/10</value></star-rating>
              </programme>
              <programme start="20260910230000" stop="20260911" channel="ghost.tv"><title></title></programme>
              <programme start="20260910230000 Europe/Paris" stop="20260911000000 Nowhere/Zone" channel="a.tv"><title><![CDATA[cdata & title]]></title></programme>
              <programme start="bad" stop="20261301000000 +0000" channel="b.tv"><title>x</title></programme>
              <programme start="1" stop="2" channel="b.tv"/>
            </tv>
            """.trimIndent().replace("&eacute;", "&#xE9;")
        )
        assertEquals(3, kotlin.channels.size)
        assertEquals(5, kotlin.programmes.size)
        assertSame(native, kotlin)
    }

    @Test
    fun truncatedFeedThrows() {
        assertTrue(LumoraNative.available)
        assertThrows(IOException::class.java) {
            XmltvParser.parse("<tv><programme channel=\"a\"><title>x".byteInputStream(), native = true)
        }
    }
}

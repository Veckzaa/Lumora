package com.lumora.util

import com.lumora.model.Channel
import com.lumora.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Films/Series "Newest" shelf. */
class NewestByDateTest {

    private fun movie(id: String, year: String? = null, releaseDate: String? = null) = Channel(
        id = id, name = id, url = "http://example/$id", mediaType = MediaType.MOVIE,
        year = year, releaseDate = releaseDate
    )

    @Test
    fun `same year puts the provider's latest addition first`() {
        val shelf = newestByDate(
            listOf(movie("900", "2026"), movie("172054", "2026"), movie("5000", "2025"), movie("171980", "2026")),
            limit = 3
        )
        assertEquals(listOf("172054", "171980", "900"), shelf.map { it.id })
    }

    @Test
    fun `a full release date still beats a bare year`() {
        val shelf = newestByDate(listOf(movie("172054", "2026"), movie("jf-abc", releaseDate = "2026-03-15")))
        assertEquals(listOf("jf-abc", "172054"), shelf.map { it.id })
    }
}

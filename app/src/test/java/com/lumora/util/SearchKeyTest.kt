package com.lumora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeyTest {

    @Test
    fun `accents fold away on both sides of a search`() {
        assertEquals("indefendable (fr)", searchKey("Indéfendable (FR)"))
        assertTrue(searchKey("Indéfendable (FR)").contains(searchKey("Indefendable")))
        assertTrue(searchKey("Indefendable: Sans Trace (FR)").contains(searchKey("indéfendable")))
    }

    @Test
    fun `plain ASCII is only lowercased`() {
        assertEquals("the office (us)", searchKey("The Office (US)"))
    }
}

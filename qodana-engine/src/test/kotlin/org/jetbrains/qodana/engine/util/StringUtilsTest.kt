package org.jetbrains.qodana.engine.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringUtilsTest {
    // --- safeSplit ---

    @Test
    fun `safeSplit valid indices`() {
        assertEquals("a", StringUtils.safeSplit("a:b:c", ":", 0))
        assertEquals("b", StringUtils.safeSplit("a:b:c", ":", 1))
        assertEquals("c", StringUtils.safeSplit("a:b:c", ":", 2))
    }

    @Test
    fun `safeSplit out of range`() {
        assertEquals("", StringUtils.safeSplit("a:b", ":", 5))
    }

    @Test
    fun `safeSplit negative index`() {
        assertEquals("", StringUtils.safeSplit("a:b", ":", -1))
    }

    @Test
    fun `safeSplit empty string`() {
        assertEquals("", StringUtils.safeSplit("", ":", 0))
    }

    @Test
    fun `safeSplit separator not found`() {
        assertEquals("abc", StringUtils.safeSplit("abc", ":", 0))
        assertEquals("", StringUtils.safeSplit("abc", ":", 1))
    }

    // --- contains ---

    @Test
    fun `contains found`() {
        assertTrue(StringUtils.contains(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `contains not found`() {
        assertFalse(StringUtils.contains(listOf("a", "b"), "z"))
    }

    @Test
    fun `contains empty list`() {
        assertFalse(StringUtils.contains(emptyList(), "a"))
    }

    // --- appendIfAbsent ---

    @Test
    fun `appendIfAbsent new element`() {
        val list = mutableListOf("a", "b")
        StringUtils.appendIfAbsent(list, "c")
        assertEquals(listOf("a", "b", "c"), list)
    }

    @Test
    fun `appendIfAbsent existing element`() {
        val list = mutableListOf("a", "b")
        StringUtils.appendIfAbsent(list, "a")
        assertEquals(listOf("a", "b"), list)
    }

    @Test
    fun `appendIfAbsent empty list`() {
        val list = mutableListOf<String>()
        StringUtils.appendIfAbsent(list, "x")
        assertEquals(listOf("x"), list)
    }

    // --- remove ---

    @Test
    fun `remove existing element`() {
        val list = mutableListOf("a", "b", "c")
        StringUtils.remove(list, "b")
        assertEquals(listOf("a", "c"), list)
    }

    @Test
    fun `remove non-existent element`() {
        val list = mutableListOf("a", "b")
        StringUtils.remove(list, "z")
        assertEquals(listOf("a", "b"), list)
    }

    @Test
    fun `remove empty list`() {
        val list = mutableListOf<String>()
        StringUtils.remove(list, "a")
        assertTrue(list.isEmpty())
    }

    // --- quoteIfSpace ---

    @Test
    fun `quoteIfSpace no space`() {
        assertEquals("abc", StringUtils.quoteIfSpace("abc"))
    }

    @Test
    fun `quoteIfSpace with space`() {
        assertEquals("\"a b\"", StringUtils.quoteIfSpace("a b"))
    }

    @Test
    fun `quoteIfSpace already quoted`() {
        assertEquals("\"a b\"", StringUtils.quoteIfSpace("\"a b\""))
    }

    @Test
    fun `quoteIfSpace empty`() {
        assertEquals("", StringUtils.quoteIfSpace(""))
    }

    @Test
    fun `quoteIfSpace path with space`() {
        assertEquals("\"/my path/file\"", StringUtils.quoteIfSpace("/my path/file"))
    }

    // --- isStringQuoted ---

    @Test
    fun `isStringQuoted true`() {
        assertTrue(StringUtils.isStringQuoted("\"hello\""))
    }

    @Test
    fun `isStringQuoted false unquoted`() {
        assertFalse(StringUtils.isStringQuoted("hello"))
    }

    @Test
    fun `isStringQuoted false partial`() {
        assertFalse(StringUtils.isStringQuoted("\"hello"))
        assertFalse(StringUtils.isStringQuoted("hello\""))
    }

    @Test
    fun `isStringQuoted false empty`() {
        assertFalse(StringUtils.isStringQuoted(""))
    }

    @Test
    fun `isStringQuoted empty quotes`() {
        assertTrue(StringUtils.isStringQuoted("\"\""))
    }

    // --- containsWinSpecialChar ---

    @Test
    fun `containsWinSpecialChar space`() {
        assertTrue(StringUtils.containsWinSpecialChar("a b"))
    }

    @Test
    fun `containsWinSpecialChar parentheses`() {
        assertTrue(StringUtils.containsWinSpecialChar("a(b)"))
    }

    @Test
    fun `containsWinSpecialChar pipe`() {
        assertTrue(StringUtils.containsWinSpecialChar("a|b"))
    }

    @Test
    fun `containsWinSpecialChar caret`() {
        assertTrue(StringUtils.containsWinSpecialChar("a^b"))
    }

    @Test
    fun `containsWinSpecialChar ampersand`() {
        assertTrue(StringUtils.containsWinSpecialChar("a&b"))
    }

    @Test
    fun `containsWinSpecialChar angle brackets`() {
        assertTrue(StringUtils.containsWinSpecialChar("a<b"))
        assertTrue(StringUtils.containsWinSpecialChar("a>b"))
    }

    @Test
    fun `containsWinSpecialChar no special chars`() {
        assertFalse(StringUtils.containsWinSpecialChar("abc"))
    }

    @Test
    fun `containsWinSpecialChar empty`() {
        assertFalse(StringUtils.containsWinSpecialChar(""))
    }

    // --- reverse ---

    @Test
    fun `reverse odd length`() {
        val list = mutableListOf("a", "b", "c")
        StringUtils.reverse(list)
        assertEquals(listOf("c", "b", "a"), list)
    }

    @Test
    fun `reverse even length`() {
        val list = mutableListOf("a", "b")
        StringUtils.reverse(list)
        assertEquals(listOf("b", "a"), list)
    }

    @Test
    fun `reverse single element`() {
        val list = mutableListOf("a")
        StringUtils.reverse(list)
        assertEquals(listOf("a"), list)
    }

    @Test
    fun `reverse empty`() {
        val list = mutableListOf<String>()
        StringUtils.reverse(list)
        assertTrue(list.isEmpty())
    }

    // --- getQuotedPath ---

    @Test
    fun `getQuotedPath no spaces`() {
        assertEquals("/path/to/file", StringUtils.getQuotedPath("/path/to/file"))
    }
}

package org.jetbrains.qodana.engine.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollectionsTest {
    // --- filter ---

    @Test
    fun `filter even numbers`() {
        assertEquals(listOf(2, 4, 6), Collections.filter(listOf(1, 2, 3, 4, 5, 6)) { it % 2 == 0 })
    }

    @Test
    fun `filter strings by length`() {
        assertEquals(listOf("bb", "ccc"), Collections.filter(listOf("a", "bb", "ccc", "d")) { it.length > 1 })
    }

    @Test
    fun `filter all false returns empty`() {
        assertTrue(Collections.filter(listOf(1, 2, 3)) { false }.isEmpty())
    }

    @Test
    fun `filter all true returns original`() {
        assertEquals(listOf(1, 2, 3), Collections.filter(listOf(1, 2, 3)) { true })
    }

    @Test
    fun `filter empty list`() {
        assertTrue(Collections.filter(emptyList<Int>()) { true }.isEmpty())
    }

    // --- unique ---

    @Test
    fun `unique removes duplicates`() {
        assertEquals(listOf(1, 2, 3, 4), Collections.unique(listOf(1, 2, 2, 3, 3, 3, 4)))
    }

    @Test
    fun `unique no duplicates`() {
        assertEquals(listOf(1, 2, 3), Collections.unique(listOf(1, 2, 3)))
    }

    @Test
    fun `unique all duplicates`() {
        assertEquals(listOf(1), Collections.unique(listOf(1, 1, 1)))
    }

    @Test
    fun `unique strings`() {
        assertEquals(listOf("a", "b", "c"), Collections.unique(listOf("a", "b", "a", "c", "b")))
    }

    @Test
    fun `unique empty list`() {
        assertTrue(Collections.unique(emptyList<Int>()).isEmpty())
    }
}

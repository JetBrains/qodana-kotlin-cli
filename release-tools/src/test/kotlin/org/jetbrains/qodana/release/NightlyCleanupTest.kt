package org.jetbrains.qodana.release

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NightlyCleanupTest {
    private fun rel(
        tag: String,
        publishedAt: String,
    ) = NightlyRelease(tag, publishedAt)

    @Test
    fun keepsAllWhenAtOrUnderLimit() {
        val r = (1..7).map { rel("v2026.2.1-nightly.d$it", "2026-06-0${it}T00:00:00Z") }
        assertEquals(emptyList<String>(), selectNightlyTagsToStrip(r, keep = 7))
    }

    @Test
    fun stripsOldestBeyondLimit() {
        val r = (1..9).map { rel("v2026.2.1-nightly.d$it", "2026-06-${"%02d".format(it)}T00:00:00Z") }
        assertEquals(listOf("v2026.2.1-nightly.d2", "v2026.2.1-nightly.d1"), selectNightlyTagsToStrip(r, keep = 7))
    }

    @Test
    fun neverTouchesStableReleases() {
        val stable = listOf(rel("v2026.2.0", "2026-06-09T00:00:00Z"), rel("v2026.1.0", "2026-06-08T00:00:00Z"))
        val nightly = (1..8).map { rel("v2026.2.1-nightly.d$it", "2026-06-0${it}T00:00:00Z") }
        assertEquals(listOf("v2026.2.1-nightly.d1"), selectNightlyTagsToStrip(stable + nightly, keep = 7))
    }

    @Test
    fun tieBreaksByTagDescForDeterminism() {
        val r =
            listOf(
                rel("v2026.2.1-nightly.20260602.1", "2026-06-02T00:00:00Z"),
                rel("v2026.2.1-nightly.20260602", "2026-06-02T00:00:00Z"),
            )
        assertEquals(listOf("v2026.2.1-nightly.20260602"), selectNightlyTagsToStrip(r, keep = 1))
    }
}

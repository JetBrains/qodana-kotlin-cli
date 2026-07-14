package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class TagRetentionTest {
    private val now = Instant.parse("2026-06-15T00:00:00Z")

    private fun tag(
        name: String,
        daysAgo: Long = 0,
    ) = RegistryTag(name, now.minus(Duration.ofDays(daysAgo)))

    @Test
    fun `keeps the newest 7 nightly generations, prunes older, keeps the moving tag`() {
        val dates = (1..9).map { "2026.2-nightly.202606%02d".format(it) } // 9 generations, .01 oldest
        val tags = dates.map { tag(it) } + tag("2026.2-nightly") // + the moving pointer
        val pruned = computeTagPrune(tags, now)
        assertEquals(setOf("2026.2-nightly.20260601", "2026.2-nightly.20260602"), pruned.toSet(), "oldest 2 pruned")
        assertEquals(false, pruned.contains("2026.2-nightly"), "the moving pointer is never pruned")
    }

    @Test
    fun `orders same-day counters numerically, not lexically`() {
        // 8 generations of one day: .0(bare-ish via no counter treated as 0), then .1..-> keep newest 7.
        val tags =
            listOf("", ".1", ".2", ".3", ".10", ".11", ".12", ".13").map { tag("2026.2-nightly.20260605$it") }
        val pruned = computeTagPrune(tags, now).toSet()
        // Newest 7 by (date, counter): 13,12,11,10,3,2,1 → the bare (counter 0) is the single oldest, pruned.
        assertEquals(setOf("2026.2-nightly.20260605"), pruned, ".10 outranks .2 numerically; only counter-0 falls off")
    }

    @Test
    fun `a date's bare and runtime variants share a generation (kept or pruned together)`() {
        val old =
            listOf("2026.2-nightly.20260601", "2026.2-nightly.20260601-clang20", "2026.2-nightly.20260601-clang16")
        val newer = (2..8).map { "2026.2-nightly.202606%02d".format(it) }
        val pruned = computeTagPrune((old + newer).map { tag(it) }, now).toSet()
        assertEquals(old.toSet(), pruned, "all variants of the oldest (8th) generation prune together")
    }

    @Test
    fun `prunes snapshots older than 7 days, keeps recent ones and the moving tag`() {
        val tags =
            listOf(
                tag("2026.2-snapshot.a1b2c3d", daysAgo = 10),
                tag("2026.2-snapshot.a1b2c3d-clang20", daysAgo = 10),
                tag("2026.2-snapshot.beef123", daysAgo = 3),
                tag("2026.2-nightly", daysAgo = 30),
            )
        val pruned = computeTagPrune(tags, now).toSet()
        val expected = setOf("2026.2-snapshot.a1b2c3d", "2026.2-snapshot.a1b2c3d-clang20")
        assertEquals(expected, pruned, "the 10-day-old snapshot (both variants) is pruned")
    }

    @Test
    fun `never touches release, eap, or rc tags`() {
        val tags = listOf("2026.2", "2026.2-eap", "2026.2-rc", "2026.2-eap-clang18").map { tag(it, daysAgo = 99) }
        assertEquals(emptyList<String>(), computeTagPrune(tags, now), "non-snapshot/nightly tags are out of scope")
    }
}

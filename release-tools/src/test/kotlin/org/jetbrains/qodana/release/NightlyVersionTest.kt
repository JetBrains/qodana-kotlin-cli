package org.jetbrains.qodana.release

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class NightlyVersionTest {
    private val day = LocalDate.of(2026, 6, 2)

    private fun compute(tags: List<String>) = computeNightlyVersion("2026.2.1", day, tags)

    @Test fun firstOfDayHasNoCounter() = assertEquals("2026.2.1-nightly.20260602", compute(emptyList()))

    @Test fun secondOfDayIsDotOne() {
        assertEquals("2026.2.1-nightly.20260602.1", compute(listOf("v2026.2.1-nightly.20260602")))
    }

    @Test fun thirdOfDayIsDotTwo() {
        val tags = listOf("v2026.2.1-nightly.20260602", "v2026.2.1-nightly.20260602.1")
        assertEquals("2026.2.1-nightly.20260602.2", compute(tags))
    }

    @Test fun maxPlusOneSurvivesGaps() { // .1 deleted: {bare, .2} -> next .3 (collision-safe)
        val tags = listOf("v2026.2.1-nightly.20260602", "v2026.2.1-nightly.20260602.2")
        assertEquals("2026.2.1-nightly.20260602.3", compute(tags))
    }

    @Test fun countsTagsWithoutVPrefix() {
        assertEquals("2026.2.1-nightly.20260602.1", compute(listOf("2026.2.1-nightly.20260602")))
    }

    @Test fun ignoresOtherDates() {
        assertEquals("2026.2.1-nightly.20260602", compute(listOf("v2026.2.1-nightly.20260601")))
    }

    @Test fun ignoresOtherBase() {
        assertEquals("2026.2.1-nightly.20260602", compute(listOf("v2026.3.0-nightly.20260602")))
    }

    @Test fun ignoresUndatedLegacyTag() {
        assertEquals("2026.2.1-nightly.20260602", compute(listOf("v2026.2.1-nightly")))
    }

    @Test
    fun ignoresNonNumericCounter() { // ".x" can't match ([0-9]+) -> tag ignored -> bare
        assertEquals("2026.2.1-nightly.20260602", compute(listOf("v2026.2.1-nightly.20260602.x")))
    }

    @Test
    fun ignoresOverlargeCounter() { // all-numeric but > Int range -> toIntOrNull null -> dropped, no throw
        assertEquals("2026.2.1-nightly.20260602", compute(listOf("v2026.2.1-nightly.20260602.99999999999")))
    }

    @Test
    fun numericMaxNotLexical() { // .10 > .2 numerically (not lexically) -> next is .11
        val tags = listOf("v2026.2.1-nightly.20260602.2", "v2026.2.1-nightly.20260602.10")
        assertEquals("2026.2.1-nightly.20260602.11", compute(tags))
    }
}

package org.jetbrains.qodana.release

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseNotesTest {
    // --- parseCommit -----
    @Test fun parsesTypeScopeAndDescription() {
        val c = parseCommit("feat(graalvm): native binary e2e tests (QD-14728) (#12)")
        assertEquals("feat", c.type)
        assertEquals("graalvm", c.scope)
        assertEquals("native binary e2e tests (QD-14728) (#12)", c.description)
    }

    @Test fun parsesTypeWithoutScope() {
        val c = parseCommit("fix: stop the crash")
        assertEquals("fix", c.type)
        assertNull(c.scope)
        assertEquals("stop the crash", c.description)
    }

    @Test fun stripsBreakingBangFromDescription() {
        val c = parseCommit("feat(api)!: drop legacy flag")
        assertEquals("feat", c.type)
        assertEquals("api", c.scope)
        assertEquals("drop legacy flag", c.description)
    }

    @Test fun lowercasesType() {
        assertEquals("fix", parseCommit("Fix: typo").type)
    }

    @Test fun keepsLaterColonsInDescription() {
        val c = parseCommit("build(deps): bump x: y to z")
        assertEquals("build", c.type)
        assertEquals("deps", c.scope)
        assertEquals("bump x: y to z", c.description)
    }

    @Test fun nonConventionalSubjectHasNullType() {
        val c = parseCommit("just some subject")
        assertNull(c.type)
        assertNull(c.scope)
        assertEquals("just some subject", c.description)
        assertEquals("just some subject", c.rawSubject)
    }

    // --- categoryOf -----
    @Test fun categorizesByType() {
        assertEquals(Category.FEATURES, categoryOf(parseCommit("feat: a")))
        assertEquals(Category.FIXES, categoryOf(parseCommit("fix: a")))
        assertEquals(Category.PERFORMANCE, categoryOf(parseCommit("perf: a")))
        assertEquals(Category.OTHER, categoryOf(parseCommit("chore: a")))
        assertEquals(Category.OTHER, categoryOf(parseCommit("docs: a")))
        assertEquals(Category.OTHER, categoryOf(parseCommit("not conventional")))
    }

    // --- renderChange -----
    @Test fun rendersScopeBold() {
        assertEquals(
            "- **graalvm**: native binary e2e tests (#12)",
            renderChange(parseCommit("feat(graalvm): native binary e2e tests (#12)")),
        )
    }

    @Test fun rendersWithoutScope() {
        assertEquals("- stop the crash", renderChange(parseCommit("fix: stop the crash")))
    }

    @Test fun rendersNonConventionalVerbatim() {
        assertEquals("- just some subject", renderChange(parseCommit("just some subject")))
    }

    // --- renderCategorized -----
    @Test fun rendersSectionsInOrderOmittingEmpty() {
        val changes =
            listOf(
                parseCommit("chore: cleanup"),
                parseCommit("feat(a): first"),
                parseCommit("fix: second"),
            )
        val expected =
            """
            ### 🚀 Features
            - **a**: first

            ### 🐛 Bug Fixes
            - second

            ### 🧹 Other changes
            - cleanup
            """.trimIndent()
        assertEquals(expected, renderCategorized(changes))
    }

    @Test fun rendersEmptyListAsEmptyString() {
        assertEquals("", renderCategorized(emptyList()))
    }

    @Test fun preservesWithinSectionOrder() {
        val changes = listOf(parseCommit("feat: one"), parseCommit("feat: two"))
        val expected =
            """
            ### 🚀 Features
            - one
            - two
            """.trimIndent()
        assertEquals(expected, renderCategorized(changes))
    }

    // --- parseNightlyTag -----
    @Test fun parsesBareNightlyTag() {
        val t = parseNightlyTag("v2026.2.1-nightly.20260605")!!
        assertEquals("2026.2.1", t.base)
        assertEquals(LocalDate.of(2026, 6, 5), t.date)
        assertNull(t.counter)
    }

    @Test fun parsesNightlyTagWithCounter() {
        assertEquals(2, parseNightlyTag("v2026.2.1-nightly.20260605.2")!!.counter)
    }

    @Test fun parsesNightlyTagWithoutVPrefix() {
        assertEquals("2026.2.1", parseNightlyTag("2026.2.1-nightly.20260605")!!.base)
    }

    @Test fun rejectsUndatedLegacyNightly() {
        assertNull(parseNightlyTag("v2026.2.1-nightly"))
    }

    @Test fun rejectsNonNightlyTag() {
        assertNull(parseNightlyTag("v2026.2.1"))
    }

    @Test fun rejectsOutOfRangeCounter() {
        // a counter is present but won't fit in Int → reject the tag (mirrors computeNightlyVersion's safety)
        assertNull(parseNightlyTag("v2026.2.1-nightly.20260605.99999999999"))
    }

    // --- nightlyTitle -----
    @Test fun titleFirstBuildHasNoCounter() {
        assertEquals(
            "Qodana 2026.2.1 Nightly (2026-06-05)",
            nightlyTitle(parseNightlyTag("v2026.2.1-nightly.20260605")!!),
        )
    }

    @Test fun titleSecondBuildIsHashTwo() {
        assertEquals(
            "Qodana 2026.2.1 Nightly (2026-06-05 #2)",
            nightlyTitle(parseNightlyTag("v2026.2.1-nightly.20260605.1")!!),
        )
    }

    @Test fun titleNthBuildIsCounterPlusOne() {
        assertEquals(
            "Qodana 2026.2.1 Nightly (2026-06-05 #11)",
            nightlyTitle(parseNightlyTag("v2026.2.1-nightly.20260605.10")!!),
        )
    }

    // --- selectPreviousNightlyTag -----
    @Test fun selectsLatestSameBaseNightlyByDateThenCounter() {
        val tags =
            listOf(
                "v2026.2.1-nightly.20260604",
                "v2026.2.1-nightly.20260605",
                "v2026.2.1-nightly.20260605.2",
                "v2026.2.1-nightly.20260605.10",
            )
        assertEquals(
            "v2026.2.1-nightly.20260605.10",
            selectPreviousNightlyTag(tags, base = "2026.2.1", excludeTag = "v2026.2.1-nightly.20260606"),
        )
    }

    @Test fun excludesCurrentTagAndOtherBases() {
        val tags =
            listOf(
                "v2026.2.1-nightly.20260606", // current — excluded
                "v2026.3.0-nightly.20260605", // other base — ignored
                "v2026.2.1-nightly.20260603", // the answer
            )
        assertEquals(
            "v2026.2.1-nightly.20260603",
            selectPreviousNightlyTag(tags, base = "2026.2.1", excludeTag = "v2026.2.1-nightly.20260606"),
        )
    }

    @Test fun tieBreaksByTagStringForDeterminism() {
        val tags = listOf("2026.2.1-nightly.20260605", "v2026.2.1-nightly.20260605")
        assertEquals(
            "v2026.2.1-nightly.20260605", // same base/date/counter → max by raw string ('v' > '2')
            selectPreviousNightlyTag(tags, base = "2026.2.1", excludeTag = "irrelevant"),
        )
    }

    @Test fun returnsNullWhenNoSameBaseNightly() {
        assertNull(
            selectPreviousNightlyTag(
                listOf("v2026.3.0-nightly.20260605"),
                base = "2026.2.1",
                excludeTag = "v2026.2.1-nightly.20260606",
            ),
        )
    }
}

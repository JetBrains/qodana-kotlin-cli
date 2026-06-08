package org.jetbrains.qodana.release

import org.junit.jupiter.api.Test
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
}

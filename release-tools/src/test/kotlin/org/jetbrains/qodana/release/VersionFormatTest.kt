package org.jetbrains.qodana.release

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VersionFormatTest {
    private fun ok(
        input: String,
        expected: String,
    ) = assertEquals(expected, normalizeReleaseVersion(input).getOrThrow())

    private fun bad(
        input: String,
        needle: String,
    ) {
        val r = normalizeReleaseVersion(input)
        assertTrue(r.isFailure, "expected failure for '$input'")
        assertTrue(r.exceptionOrNull()!!.message!!.contains(needle), "message=${r.exceptionOrNull()!!.message}")
    }

    @Test fun addsVPrefix() = ok("2026.2", "v2026.2")

    @Test fun keepsVPrefix() = ok("v2026.2", "v2026.2")

    @Test fun omitsZeroPatch() = ok("2026.2.0", "v2026.2")

    @Test fun keepsNonZeroPatch() = ok("2026.2.1", "v2026.2.1")

    @Test fun nightlyPrerelease() = ok("2026.2.1-nightly.20260602", "v2026.2.1-nightly.20260602")

    @Test fun nightlyWithCounter() = ok("v2026.2.1-nightly.20260602.3", "v2026.2.1-nightly.20260602.3")

    @Test fun prereleaseAndBuild() = ok("v2026.2-nightly.20260602+abc1234", "v2026.2-nightly.20260602+abc1234")

    @Test fun buildAllowsLeadingZeroNumeric() = ok("2026.2+01", "v2026.2+01")

    @Test fun rejectsOneSegment() = bad("2026", "core")

    @Test fun rejectsFourSegments() = bad("2026.2.1.0", "core")

    @Test fun rejectsLeadingZeroCore() = bad("2026.02", "core")

    @Test fun rejectsWhitespace() = bad(" 2026.2 ", "whitespace")

    @Test fun rejectsEmpty() = bad("", "empty")

    @Test fun rejectsEmptyPrerelease() = bad("2026.2-", "pre-release")

    @Test fun rejectsEmptyPrereleaseIdentifier() = bad("2026.2-nightly..x", "identifier")

    @Test fun rejectsLeadingZeroNumericPrerelease() = bad("2026.2-nightly.01", "leading zero")
}

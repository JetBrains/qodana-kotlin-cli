package internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure-Kotlin tests for [computeVersionState]. No Gradle API; runs in milliseconds.
 *
 * Contract:
 *  - `source` is the literal string from `gradle.properties`'s `version=` line.
 *  - `lastStableTag` is the most recent `v*` tag with `-nightly`/`-tagprobe-` filtered out by the caller.
 *  - Returns one of [VersionState]'s four cases.
 */
class VersionComputeTest {
    // Dev state =====

    @Test
    @DisplayName("dev source with no prior tag → Dev")
    fun devNoTag() {
        assertEquals(VersionState.Dev, computeVersionState("dev", null))
    }

    @Test
    @DisplayName("dev source bypasses bump rule even when a release exists")
    fun devWithPriorTag() {
        assertEquals(VersionState.Dev, computeVersionState("dev", "v2026.3.0"))
    }

    // JustReleased state =====

    @Test
    @DisplayName("source matches last tag → JustReleased with patch+1 nightly base")
    fun justReleased() {
        assertEquals(
            VersionState.JustReleased(nextBase = "v2026.3.1"),
            computeVersionState("2026.3.0", "v2026.3.0"),
        )
    }

    @Test
    @DisplayName("first-ever release (no prior tag) numeric source → JustReleased")
    fun firstEverRelease() {
        assertEquals(
            VersionState.JustReleased(nextBase = "v2026.3.1"),
            computeVersionState("2026.3.0", null),
        )
    }

    // BumpAhead state =====

    @Test
    @DisplayName("patch bump")
    fun bumpPatch() {
        assertEquals(
            VersionState.BumpAhead(nextBase = "v2026.3.1"),
            computeVersionState("2026.3.1", "v2026.3.0"),
        )
    }

    @Test
    @DisplayName("minor bump")
    fun bumpMinor() {
        assertEquals(
            VersionState.BumpAhead(nextBase = "v2026.4.0"),
            computeVersionState("2026.4.0", "v2026.3.0"),
        )
    }

    @Test
    @DisplayName("minor bump with omitted patch normalizes to .0")
    fun bumpMinorOmittedPatch() {
        assertEquals(
            VersionState.BumpAhead(nextBase = "v2026.4.0"),
            computeVersionState("2026.4", "v2026.3.0"),
        )
    }

    @Test
    @DisplayName("major bump (CalVer year)")
    fun bumpMajor() {
        assertEquals(
            VersionState.BumpAhead(nextBase = "v2027.0.0"),
            computeVersionState("2027.0.0", "v2026.3.0"),
        )
    }

    // Invalid: skipped segments =====

    @Test
    @DisplayName("patch skip rejected (2026.3.1 → 2026.3.3 skips 2026.3.2)")
    fun rejectPatchSkip() {
        val state = computeVersionState("2026.3.3", "v2026.3.1")
        val invalid = assertInstanceOf(VersionState.Invalid::class.java, state)
        assertTrue(
            invalid.message.contains("2026.3.2") || invalid.message.contains("v2026.3.2"),
            "Invalid message should mention the closest valid candidate: ${invalid.message}",
        )
    }

    @Test
    @DisplayName("minor skip rejected (2026.3.0 → 2026.5 skips 2026.4)")
    fun rejectMinorSkip() {
        val state = computeVersionState("2026.5", "v2026.3.0")
        assertInstanceOf(VersionState.Invalid::class.java, state)
    }

    @Test
    @DisplayName("major skip rejected (2026.3.0 → 2028.0.0 skips 2027.0.0)")
    fun rejectMajorSkip() {
        val state = computeVersionState("2028.0.0", "v2026.3.0")
        assertInstanceOf(VersionState.Invalid::class.java, state)
    }

    // Invalid: malformed source =====

    @Test
    @DisplayName("empty source rejected")
    fun rejectEmpty() {
        val state = computeVersionState("", "v2026.3.0")
        val invalid = assertInstanceOf(VersionState.Invalid::class.java, state)
        assertTrue(invalid.message.contains("empty"), "got: ${invalid.message}")
    }

    @Test
    @DisplayName("non-numeric source rejected")
    fun rejectNonNumeric() {
        val state = computeVersionState("abc", "v2026.3.0")
        assertInstanceOf(VersionState.Invalid::class.java, state)
    }

    @Test
    @DisplayName("one-segment source rejected (fewer than 2 segments)")
    fun rejectOneSegment() {
        assertInstanceOf(
            VersionState.Invalid::class.java,
            computeVersionState("2026", "v2026.3.0"),
        )
    }

    @Test
    @DisplayName("four-segment source rejected (more than 3 segments)")
    fun rejectFourSegments() {
        assertInstanceOf(
            VersionState.Invalid::class.java,
            computeVersionState("2026.3.0.1", "v2026.3.0"),
        )
    }

    @Test
    @DisplayName("leading zero in segment rejected")
    fun rejectLeadingZero() {
        val state = computeVersionState("2026.03.0", "v2026.3.0")
        val invalid = assertInstanceOf(VersionState.Invalid::class.java, state)
        assertTrue(invalid.message.contains("leading zero"), "got: ${invalid.message}")
    }

    @Test
    @DisplayName("whitespace around source rejected")
    fun rejectWhitespace() {
        val state = computeVersionState(" 2026.3.0 ", "v2026.3.0")
        val invalid = assertInstanceOf(VersionState.Invalid::class.java, state)
        assertTrue(invalid.message.contains("whitespace"), "got: ${invalid.message}")
    }

    @Test
    @DisplayName("pre-release suffix on source rejected")
    fun rejectPreReleaseSuffix() {
        val state = computeVersionState("2026.3.0-rc1", "v2026.3.0")
        val invalid = assertInstanceOf(VersionState.Invalid::class.java, state)
        assertTrue(
            invalid.message.contains("suffix") || invalid.message.contains("rc1"),
            "got: ${invalid.message}",
        )
    }

    // Zero-valued segments (edge of valid) =====

    @Test
    @DisplayName("zero in segment (not leading) is valid")
    fun zeroValidInPatch() {
        assertEquals(
            VersionState.JustReleased(nextBase = "v2026.4.1"),
            computeVersionState("2026.4.0", "v2026.4.0"),
        )
    }
}

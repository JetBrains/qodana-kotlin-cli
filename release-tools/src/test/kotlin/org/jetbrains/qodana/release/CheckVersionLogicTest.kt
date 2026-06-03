package org.jetbrains.qodana.release

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CheckVersionLogicTest {
    @Test
    fun bumpAheadOkForTagged() =
        // BumpAhead is the canonical tagged-release state; the gate must accept it.
        assertNull(requireExactError("2026.4", "2026.4", VersionState.BumpAhead("v2026.4"), lastTag = "v2026.3.0"))

    @Test
    fun canonicalEquivalenceAccepted() =
        // 2026.4 and 2026.4.0 are the same release
        assertNull(requireExactError("2026.4", "2026.4.0", VersionState.BumpAhead("v2026.4"), lastTag = "v2026.3.0"))

    @Test
    fun justReleasedOkOnlyWhenFirstEver() {
        assertNull(requireExactError("2026.4", "2026.4", VersionState.JustReleased("v2026.4.1"), lastTag = null))
        val withPrior =
            requireExactError("2026.4", "2026.4", VersionState.JustReleased("v2026.4.1"), lastTag = "v2026.4")
        assertNotNull(withPrior)
        assertTrue(withPrior!!.contains("BumpAhead"))
    }

    @Test
    fun mismatchReported() {
        val msg = requireExactError("2026.3", "2026.4", VersionState.BumpAhead("v2026.4"), lastTag = "v2026.3.0")
        assertNotNull(msg)
        assertTrue(msg!!.contains("mismatch"))
    }

    @Test
    fun unparseableSourceReportedAsMismatch() {
        // version=dev is a valid state (Dev), but can't be tagged: parse fails -> mismatch.
        val devMsg = requireExactError("dev", "2026.4", VersionState.Dev, lastTag = null)
        assertNotNull(devMsg)
        assertTrue(devMsg!!.contains("mismatch"))
        // requireExact side unparseable also -> mismatch.
        val badReq = requireExactError("2026.4", "garbage", VersionState.BumpAhead("v2026.4"), lastTag = "v2026.3.0")
        assertNotNull(badReq)
        assertTrue(badReq!!.contains("mismatch"))
    }
}

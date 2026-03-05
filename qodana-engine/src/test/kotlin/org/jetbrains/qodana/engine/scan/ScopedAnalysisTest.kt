package org.jetbrains.qodana.engine.scan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopedAnalysisTest {

    // --- Script string building ---

    @Test
    fun `scopedScript builds correct string`() {
        assertEquals("scoped:changes.json", ScopedAnalysis.scopedScript("changes.json"))
    }

    @Test
    fun `reverseScopedScript NEW stage`() {
        assertEquals("reverse-scoped:NEW,changes.json", ScopedAnalysis.reverseScopedScript(ScopedAnalysis.ReverseStage.NEW, "changes.json"))
    }

    @Test
    fun `reverseScopedScript OLD stage`() {
        assertEquals("reverse-scoped:OLD,changes.json", ScopedAnalysis.reverseScopedScript(ScopedAnalysis.ReverseStage.OLD, "changes.json"))
    }

    @Test
    fun `reverseScopedScript FIXES stage`() {
        assertEquals("reverse-scoped:FIXES,changes.json", ScopedAnalysis.reverseScopedScript(ScopedAnalysis.ReverseStage.FIXES, "changes.json"))
    }

    // --- Scoped stages ---

    @Test
    fun `firstStageOfScoped skips result and coverage`() {
        val stage = ScopedAnalysis.firstStageOfScoped("scope.json")
        assertEquals("scoped:scope.json", stage.script)
        assertTrue(stage.skipResult)
        assertTrue(stage.skipCoverageComputation)
        assertNull(stage.baselineSarif)
        assertEquals("start", stage.resultsSubDir)
    }

    @Test
    fun `secondStageOfScoped uses baseline`() {
        val stage = ScopedAnalysis.secondStageOfScoped("scope.json", "/path/to/start.sarif")
        assertEquals("scoped:scope.json", stage.script)
        assertFalse(stage.skipResult)
        assertFalse(stage.skipCoverageComputation)
        assertEquals("/path/to/start.sarif", stage.baselineSarif)
        assertEquals("end", stage.resultsSubDir)
    }

    // --- Reverse scoped stages ---

    @Test
    fun `firstStageOfReverseScoped with reduced scope`() {
        val stage = ScopedAnalysis.firstStageOfReverseScoped("scope.json", "reduced-scope.json")
        assertEquals("reverse-scoped:NEW,scope.json", stage.script)
        assertEquals(ScopedAnalysis.FinishStrategy.ANY, stage.finishStrategy)
        assertEquals("reduced-scope.json", stage.reducedScopePath)
        assertEquals("end", stage.resultsSubDir)
    }

    @Test
    fun `firstStageOfReverseScoped without reduced scope`() {
        val stage = ScopedAnalysis.firstStageOfReverseScoped("scope.json")
        assertNull(stage.reducedScopePath)
    }

    @Test
    fun `secondStageOfReverseScoped no fixes`() {
        val stage = ScopedAnalysis.secondStageOfReverseScoped("scope.json", "/start.sarif")
        assertEquals("reverse-scoped:OLD,scope.json", stage.script)
        assertEquals(ScopedAnalysis.FinishStrategy.NEVER, stage.finishStrategy)
        assertEquals("/start.sarif", stage.baselineSarif)
        assertEquals("start", stage.resultsSubDir)
    }

    @Test
    fun `secondStageOfReverseScoped with fixes`() {
        val stage = ScopedAnalysis.secondStageOfReverseScoped("scope.json", "/start.sarif", applyFixes = true)
        assertEquals(ScopedAnalysis.FinishStrategy.FIXABLE, stage.finishStrategy)
    }

    @Test
    fun `secondStageOfReverseScoped with cleanup`() {
        val stage = ScopedAnalysis.secondStageOfReverseScoped("scope.json", "/start.sarif", cleanup = true)
        assertEquals(ScopedAnalysis.FinishStrategy.FIXABLE, stage.finishStrategy)
    }

    @Test
    fun `thirdStageOfReverseScoped for fixes`() {
        val stage = ScopedAnalysis.thirdStageOfReverseScoped("scope.json", "/start.sarif")
        assertEquals("reverse-scoped:FIXES,scope.json", stage.script)
        assertEquals(ScopedAnalysis.FinishStrategy.NEVER, stage.finishStrategy)
        assertEquals("/start.sarif", stage.baselineSarif)
        assertEquals("fixes", stage.resultsSubDir)
    }
}

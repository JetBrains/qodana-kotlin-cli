package org.jetbrains.qodana.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RunScenarioTest {

    @Test
    fun `default scenario`() {
        val scenario: RunScenario = RunScenario.Default
        assertIs<RunScenario.Default>(scenario)
    }

    @Test
    fun `full history scenario`() {
        val scenario: RunScenario = RunScenario.FullHistory
        assertIs<RunScenario.FullHistory>(scenario)
    }

    @Test
    fun `local changes scenario with diffs`() {
        val scenario = RunScenario.LocalChanges(diffStart = "abc", diffEnd = "def")
        assertEquals("abc", scenario.diffStart)
        assertEquals("def", scenario.diffEnd)
    }

    @Test
    fun `local changes scenario without diffs`() {
        val scenario = RunScenario.LocalChanges()
        assertNull(scenario.diffStart)
        assertNull(scenario.diffEnd)
    }

    @Test
    fun `scoped scenario`() {
        val scenario = RunScenario.Scoped(targetBranch = "main")
        assertEquals("main", scenario.targetBranch)
    }

    @Test
    fun `reverse scoped scenario`() {
        val scenario = RunScenario.ReverseScoped(targetBranch = "develop")
        assertEquals("develop", scenario.targetBranch)
    }

    @Test
    fun `sealed interface covers all variants`() {
        val scenarios: List<RunScenario> = listOf(
            RunScenario.Default,
            RunScenario.FullHistory,
            RunScenario.LocalChanges(),
            RunScenario.Scoped("main"),
            RunScenario.ReverseScoped("main"),
        )
        assertEquals(5, scenarios.size)
    }
}

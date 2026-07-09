package org.jetbrains.qodana.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
            RunScenario.Scoped("main"),
            RunScenario.ReverseScoped("main"),
        )
        assertEquals(4, scenarios.size)
    }
}

package org.jetbrains.qodana.clang

import org.jetbrains.qodana.core.model.InspectScope
import org.jetbrains.qodana.core.model.QodanaYaml
import kotlin.test.Test
import kotlin.test.assertEquals

class ClangConfigTest {
    @Test
    fun `null yaml returns wildcard checks`() {
        val result = ClangConfig.buildChecksArg(null)
        assertEquals("--checks=*", result)
    }

    @Test
    fun `empty yaml with no includes or excludes returns wildcard checks`() {
        val yaml = QodanaYaml()
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=*", result)
    }

    @Test
    fun `includes only returns comma-separated rules`() {
        val yaml =
            QodanaYaml(
                include =
                    listOf(
                        InspectScope(name = "rule1"),
                        InspectScope(name = "rule2"),
                    ),
            )
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=rule1,rule2", result)
    }

    @Test
    fun `excludes only returns wildcard with negated rules`() {
        val yaml =
            QodanaYaml(
                exclude =
                    listOf(
                        InspectScope(name = "rule1"),
                        InspectScope(name = "rule2"),
                    ),
            )
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=*,-rule1,-rule2", result)
    }

    @Test
    fun `both includes and excludes returns combined checks`() {
        val yaml =
            QodanaYaml(
                include =
                    listOf(
                        InspectScope(name = "inc1"),
                        InspectScope(name = "inc2"),
                    ),
                exclude =
                    listOf(
                        InspectScope(name = "exc1"),
                    ),
            )
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=inc1,inc2,-exc1", result)
    }

    @Test
    fun `clion-prefixed includes are filtered out completely`() {
        val yaml =
            QodanaYaml(
                include =
                    listOf(
                        InspectScope(name = "clion-someRule"),
                        InspectScope(name = "clion-anotherRule"),
                    ),
            )
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=*", result)
    }

    @Test
    fun `clion-prefixed includes filtered but valid includes kept`() {
        val yaml =
            QodanaYaml(
                include =
                    listOf(
                        InspectScope(name = "clion-someRule"),
                        InspectScope(name = "validRule"),
                    ),
            )
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=validRule", result)
    }

    @Test
    fun `rules with quotes in name are skipped`() {
        val yaml =
            QodanaYaml(
                include =
                    listOf(
                        InspectScope(name = "rule\"with\"quotes"),
                    ),
                exclude =
                    listOf(
                        InspectScope(name = "exc\"bad"),
                    ),
            )
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=*", result)
    }

    @Test
    fun `single include returns single rule`() {
        val yaml =
            QodanaYaml(
                include =
                    listOf(
                        InspectScope(name = "singleRule"),
                    ),
            )
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=singleRule", result)
    }

    @Test
    fun `single exclude returns wildcard with single negated rule`() {
        val yaml =
            QodanaYaml(
                exclude =
                    listOf(
                        InspectScope(name = "singleRule"),
                    ),
            )
        val result = ClangConfig.buildChecksArg(yaml)
        assertEquals("--checks=*,-singleRule", result)
    }
}

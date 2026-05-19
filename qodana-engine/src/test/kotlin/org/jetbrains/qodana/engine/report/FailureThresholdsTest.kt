package org.jetbrains.qodana.engine.report

import org.jetbrains.qodana.core.model.QodanaYaml
import org.jetbrains.qodana.core.model.YamlFailureConditions
import org.jetbrains.qodana.core.model.YamlSeverityThresholds
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FailureThresholdsTest {
    @Test
    fun `empty yaml and no option`() {
        val thresholds = FailureThresholds.getFailureThresholds(QodanaYaml())
        val args = FailureThresholds.thresholdsToArgs(thresholds).sorted().joinToString(" ") { it }
        assertEquals("", args)
    }

    @Test
    fun `failThreshold set to 0`() {
        val yaml = QodanaYaml(failThreshold = 0)
        val thresholds = FailureThresholds.getFailureThresholds(yaml)
        val args = FailureThresholds.thresholdsToArgs(thresholds).sorted().joinToString("") { " $it" }
        assertEquals(" --threshold-any=0", args)
    }

    @Test
    fun `multiple severity thresholds`() {
        val yaml =
            QodanaYaml(
                failureConditions =
                    YamlFailureConditions(
                        severityThresholds =
                            YamlSeverityThresholds(
                                any = 1,
                                critical = 2,
                                high = 3,
                                moderate = 4,
                                low = 5,
                                info = 6,
                            ),
                    ),
            )
        val thresholds = FailureThresholds.getFailureThresholds(yaml)
        val args = FailureThresholds.thresholdsToArgs(thresholds).sorted().joinToString("") { " $it" }
        assertEquals(
            " --threshold-any=1 --threshold-critical=2 --threshold-high=3 --threshold-info=6 --threshold-low=5 --threshold-moderate=4",
            args,
        )
    }

    @Test
    fun `severity thresholds override failThreshold when any is set`() {
        val yaml =
            QodanaYaml(
                failThreshold = 123,
                failureConditions =
                    YamlFailureConditions(
                        severityThresholds =
                            YamlSeverityThresholds(
                                any = 1,
                                critical = 2,
                                high = 3,
                                moderate = 4,
                                low = 5,
                                info = 6,
                            ),
                    ),
            )
        val thresholds = FailureThresholds.getFailureThresholds(yaml)
        val args = FailureThresholds.thresholdsToArgs(thresholds).sorted().joinToString("") { " $it" }
        assertEquals(
            " --threshold-any=1 --threshold-critical=2 --threshold-high=3 --threshold-info=6 --threshold-low=5 --threshold-moderate=4",
            args,
        )
    }

    @Test
    fun `failThreshold fills any when severity thresholds lack any`() {
        val yaml =
            QodanaYaml(
                failThreshold = 123,
                failureConditions =
                    YamlFailureConditions(
                        severityThresholds =
                            YamlSeverityThresholds(
                                critical = 2,
                                high = 3,
                                moderate = 4,
                                low = 5,
                                info = 6,
                            ),
                    ),
            )
        val thresholds = FailureThresholds.getFailureThresholds(yaml)
        val args = FailureThresholds.thresholdsToArgs(thresholds).sorted().joinToString("") { " $it" }
        assertEquals(
            " --threshold-any=123 --threshold-critical=2 --threshold-high=3 --threshold-info=6 --threshold-low=5 --threshold-moderate=4",
            args,
        )
    }

    @Test
    fun `cli option overrides yaml settings`() {
        val yaml =
            QodanaYaml(
                failureConditions =
                    YamlFailureConditions(
                        severityThresholds =
                            YamlSeverityThresholds(
                                any = 1,
                                critical = 2,
                                high = 3,
                                moderate = 4,
                                low = 5,
                                info = 6,
                            ),
                    ),
            )
        val thresholds = FailureThresholds.getFailureThresholds(yaml, cliFailThreshold = "123")
        val args = FailureThresholds.thresholdsToArgs(thresholds).sorted().joinToString("") { " $it" }
        assertEquals(" --threshold-any=123", args)
    }

    @Test
    fun `null yaml returns empty`() {
        val thresholds = FailureThresholds.getFailureThresholds(null)
        assertEquals(emptyMap(), thresholds)
    }
}

package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.model.QodanaYaml
import org.jetbrains.qodana.core.model.YamlPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginUtilsTest {
    @Test
    fun `getPluginIds empty plugins`() {
        val yaml = QodanaYaml(plugins = emptyList())
        assertTrue(PluginUtils.getPluginIds(yaml).isEmpty())
    }

    @Test
    fun `getPluginIds single plugin`() {
        val yaml = QodanaYaml(plugins = listOf(YamlPlugin(id = "plugin1")))
        assertEquals(listOf("plugin1"), PluginUtils.getPluginIds(yaml))
    }

    @Test
    fun `getPluginIds multiple plugins`() {
        val yaml =
            QodanaYaml(
                plugins =
                    listOf(
                        YamlPlugin(id = "plugin1"),
                        YamlPlugin(id = "plugin2"),
                        YamlPlugin(id = "plugin3"),
                    ),
            )
        assertEquals(listOf("plugin1", "plugin2", "plugin3"), PluginUtils.getPluginIds(yaml))
    }

    @Test
    fun `getPluginIds null yaml`() {
        assertTrue(PluginUtils.getPluginIds(null).isEmpty())
    }
}

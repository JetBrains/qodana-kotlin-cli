package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.model.QodanaYaml

object PluginUtils {

    /**
     * Extracts plugin IDs from a QodanaYaml's plugin list.
     */
    fun getPluginIds(yaml: QodanaYaml?): List<String> {
        return yaml?.plugins?.map { it.id } ?: emptyList()
    }
}

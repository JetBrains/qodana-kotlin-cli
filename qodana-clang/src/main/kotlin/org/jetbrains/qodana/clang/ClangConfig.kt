package org.jetbrains.qodana.clang

import org.jetbrains.qodana.core.model.QodanaYaml
import org.slf4j.LoggerFactory

object ClangConfig {
    private val logger = LoggerFactory.getLogger(ClangConfig::class.java)

    fun buildChecksArg(yaml: QodanaYaml?): String {
        if (yaml == null) return "--checks=*"

        val includeRules = mutableListOf<String>()
        val excludeRules = mutableListOf<String>()

        for (include in yaml.include) {
            val name = include.name.trim()
            if (name.startsWith("clion-")) continue
            if (name.contains("\"")) {
                logger.warn("Skipping include rule with invalid characters: {}", name)
                continue
            }
            includeRules.add(name)
        }
        for (exclude in yaml.exclude) {
            val name = exclude.name
            if (name.contains("\"")) {
                logger.warn("Skipping exclude rule with invalid characters: {}", name)
                continue
            }
            excludeRules.add(name)
        }

        val plusChecks = includeRules.joinToString(",")
        val minusChecks = excludeRules.joinToString(",") { "-$it" }

        return when {
            plusChecks.isNotEmpty() && minusChecks.isNotEmpty() -> "--checks=$plusChecks,$minusChecks"
            plusChecks.isNotEmpty() -> "--checks=$plusChecks"
            minusChecks.isNotEmpty() -> "--checks=*,$minusChecks"
            else -> "--checks=*"
        }
    }
}

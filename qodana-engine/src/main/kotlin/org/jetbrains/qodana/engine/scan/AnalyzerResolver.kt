package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.product.Analyzer
import org.jetbrains.qodana.core.product.Linters

object AnalyzerResolver {
    /**
     * Resolves an analyzer from the IDE field in qodana.yaml.
     * Returns null if the IDE code is not recognized.
     */
    fun resolveFromIdeField(ideField: String?): Analyzer? {
        if (ideField.isNullOrBlank()) return null
        val isEap = ideField.endsWith(Linters.EAP_SUFFIX)
        val code = ideField.removeSuffix(Linters.EAP_SUFFIX)
        val linter = Linters.findByProductCode(code) ?: return null
        return Analyzer.Native(linter, isEap = isEap || linter.eapOnly)
    }

    /**
     * Resolves an analyzer from a docker image string.
     */
    fun resolveFromDockerImage(image: String?): Analyzer? {
        if (image.isNullOrBlank()) return null
        val linter = Linters.findByDockerImage(image) ?: return null
        return Analyzer.Docker(linter, image = image)
    }

    /**
     * Resolves an analyzer from a linter name string.
     */
    fun resolveFromLinterName(name: String?): Analyzer? {
        if (name.isNullOrBlank()) return null
        val isEap = name.endsWith(Linters.EAP_SUFFIX)
        val linter = Linters.findByName(name) ?: return null
        return Analyzer.Native(linter, isEap = isEap || linter.eapOnly)
    }
}

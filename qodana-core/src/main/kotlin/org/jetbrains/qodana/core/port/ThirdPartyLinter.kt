package org.jetbrains.qodana.core.port

import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import java.nio.file.Path

interface ThirdPartyLinter {
    fun mountTools(targetPath: Path): Map<String, Path>
    suspend fun runAnalysis(context: ThirdPartyScanContext)
}

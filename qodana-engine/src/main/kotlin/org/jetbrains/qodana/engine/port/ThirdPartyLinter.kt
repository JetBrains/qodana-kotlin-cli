package org.jetbrains.qodana.engine.port

import org.jetbrains.qodana.core.model.QodanaError
import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import java.nio.file.Path

interface ThirdPartyLinter {
    fun mountTools(targetPath: Path): Result<Unit>
    suspend fun runAnalysis(context: ThirdPartyScanContext): Result<Unit>
}

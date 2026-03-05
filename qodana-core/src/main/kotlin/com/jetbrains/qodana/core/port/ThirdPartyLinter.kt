package com.jetbrains.qodana.core.port

import com.jetbrains.qodana.core.model.QodanaError
import com.jetbrains.qodana.core.model.ThirdPartyScanContext
import java.nio.file.Path

interface ThirdPartyLinter {
    fun mountTools(targetPath: Path): Result<Unit>
    suspend fun runAnalysis(context: ThirdPartyScanContext): Result<Unit>
}

package com.jetbrains.qodana.core.port

import java.nio.file.Path

interface WebUiProvider {
    fun extractWebUi(targetDir: Path)
}

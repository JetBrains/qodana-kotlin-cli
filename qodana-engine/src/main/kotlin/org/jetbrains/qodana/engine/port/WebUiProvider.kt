package org.jetbrains.qodana.engine.port

import java.nio.file.Path

interface WebUiProvider {
    fun extractWebUi(targetDir: Path)
}

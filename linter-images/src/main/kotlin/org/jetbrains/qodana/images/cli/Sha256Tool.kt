package org.jetbrains.qodana.images.cli

import java.nio.file.Path
import org.jetbrains.qodana.images.process.CommandRunner

/** Computes a file's hex sha256 via the `sha256sum` subprocess (faked in tests). */
class Sha256Tool(private val runner: CommandRunner) {
    fun sha256(file: Path): String {
        val result = runner.run(listOf("sha256sum", file.toString()))
        require(result.exitCode == 0) { "sha256sum failed (${result.exitCode}): ${result.stderr}" }
        return result.stdout.trim().substringBefore(' ').lowercase()
    }
}

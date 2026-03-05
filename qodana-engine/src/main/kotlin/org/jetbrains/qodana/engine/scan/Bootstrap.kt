package org.jetbrains.qodana.engine.scan

import java.nio.file.Path

object Bootstrap {

    /**
     * Executes a bootstrap command string.
     * Returns the exit code, or 0 if command is empty.
     */
    fun execute(command: String, workDir: Path): Int {
        if (command.isBlank()) return 0

        val os = System.getProperty("os.name", "").lowercase()
        val processArgs = if (os.contains("win")) {
            listOf("cmd", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }

        val process = ProcessBuilder(processArgs)
            .directory(workDir.toFile())
            .inheritIO()
            .start()

        return process.waitFor()
    }

    /**
     * Executes a command and captures stdout and stderr separately.
     * Returns triple of (exitCode, stdout, stderr).
     */
    fun executeWithCapture(command: String, workDir: Path): Triple<Int, String, String> {
        if (command.isBlank()) return Triple(0, "", "")

        val os = System.getProperty("os.name", "").lowercase()
        val processArgs = if (os.contains("win")) {
            listOf("cmd", "/c", command)
        } else {
            listOf("sh", "-c", command)
        }

        val process = ProcessBuilder(processArgs)
            .directory(workDir.toFile())
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return Triple(exitCode, stdout, stderr)
    }
}

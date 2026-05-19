package org.jetbrains.qodana.clang

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.model.ThirdPartyScanContext
import org.jetbrains.qodana.core.port.ProcessRunner
import org.jetbrains.qodana.core.port.Terminal
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class ClangRunner(
    private val processRunner: ProcessRunner,
    private val terminal: Terminal,
) {
    private val logger = LoggerFactory.getLogger(ClangRunner::class.java)

    suspend fun runParallel(
        context: ThirdPartyScanContext,
        files: List<FileWithHeaders>,
        checks: String,
        clangPath: Path,
    ) {
        val tmpResultsDir = context.paths.resultsDir.resolve("tmp")
        Files.createDirectories(tmpResultsDir)

        val logDir = context.logDir
        Files.createDirectories(logDir)
        val stdoutLog = logDir.resolve("clang-out.txt")
        val stderrLog = logDir.resolve("clang-err.txt")

        val cpuCount = Runtime.getRuntime().availableProcessors()
        val semaphore = Semaphore(cpuCount)
        val total = files.size
        var completed = 0

        terminal.println("Running clang-tidy on $total files with $cpuCount parallel workers...")

        coroutineScope {
            files.forEachIndexed { counter, fileWithHeaders ->
                launch {
                    semaphore.acquire()
                    try {
                        completed++
                        terminal.print("\r $completed/$total")

                        runClangTidy(
                            counter = counter,
                            input = fileWithHeaders,
                            checks = checks,
                            context = context,
                            clangPath = clangPath,
                            tmpResultsDir = tmpResultsDir,
                            stdoutLog = stdoutLog,
                            stderrLog = stderrLog,
                        )
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
        terminal.println("")
    }

    private suspend fun runClangTidy(
        counter: Int,
        input: FileWithHeaders,
        checks: String,
        context: ThirdPartyScanContext,
        clangPath: Path,
        tmpResultsDir: Path,
        stdoutLog: Path,
        stderrLog: Path,
    ) {
        val sarifOutput = tmpResultsDir.resolve("$counter.sarif.json")
        val compileCommands = context.compileCommands ?: return

        val args =
            buildList {
                add(checks)
                add("-p")
                add(compileCommands)
                add("--export-sarif")
                add(sarifOutput.toString())
                addAll(input.headers)
                add(input.file)
                add("--quiet")
                if (context.clangArgs.isNotBlank()) {
                    addAll(context.clangArgs.split(" ").filter { it.isNotBlank() })
                }
            }

        val result =
            processRunner.run(
                ProcessSpec(
                    command = clangPath.toString(),
                    args = args,
                    workDir = context.paths.projectDir,
                ),
            )

        if (result.stderr.isNotBlank()) {
            logger.debug(result.stderr)
            appendToFile(stderrLog, "File: ${input.file}\n${result.stderr}\n")
        }
        if (result.stdout.isNotBlank()) {
            logger.debug(result.stdout)
            appendToFile(stdoutLog, "File: ${input.file}\n${result.stdout}\n${result.stderr}\n")
        }
    }

    private fun appendToFile(
        path: Path,
        content: String,
    ) {
        Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

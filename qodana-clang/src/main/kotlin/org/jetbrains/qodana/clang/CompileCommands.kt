package org.jetbrains.qodana.clang

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.core.port.ProcessRunner
import org.slf4j.LoggerFactory
import java.nio.file.Path

data class CompileCommand(
    val directory: String = "",
    val command: String = "",
    val file: String = "",
    val output: String = "",
    val arguments: List<String> = emptyList(),
)

data class FileWithHeaders(
    val file: String,
    val headers: List<String>,
)

class CompileCommands(
    private val processRunner: ProcessRunner,
) {
    private val logger = LoggerFactory.getLogger(CompileCommands::class.java)
    private val mapper = ObjectMapper().registerModule(kotlinModule())

    companion object {
        private const val SEARCH_START = "#include <...> search starts here:"
        private const val SEARCH_END = "End of search list."
    }

    suspend fun getFilesAndCompilers(compileCommandsPath: Path): List<FileWithHeaders> {
        val commands: List<CompileCommand> = mapper.readValue(compileCommandsPath.toFile())
        val headerCache = mutableMapOf<String, List<String>>()

        return commands.mapNotNull { cmd ->
            val compiler =
                when {
                    cmd.command.isNotBlank() ->
                        cmd.command
                            .trim()
                            .split(" ")
                            .first()
                    cmd.arguments.isNotEmpty() -> cmd.arguments.first()
                    else -> {
                        logger.warn("Empty command and arguments for file in compilation db: {}", cmd.file)
                        return@mapNotNull null
                    }
                }

            val headerType = getHeaderType(cmd.file)
            val cacheKey = compiler + headerType
            val headers =
                headerCache.getOrPut(cacheKey) {
                    askCompiler(compiler, headerType)
                }
            FileWithHeaders(file = cmd.file, headers = headers)
        }
    }

    private fun getHeaderType(file: String): String {
        val ext = file.substringAfterLast('.', "")
        val nullDevice = if (System.getProperty("os.name").lowercase().contains("win")) "NUL" else "/dev/null"
        return when (ext) {
            "c", "h" -> "-E -Wp,-v -xc $nullDevice"
            else -> "-E -Wp,-v -xc++ $nullDevice"
        }
    }

    private suspend fun askCompiler(
        compiler: String,
        headerType: String,
    ): List<String> {
        val result =
            processRunner.run(
                ProcessSpec(
                    command = compiler,
                    args = headerType.split(" "),
                ),
            )

        val stderr = result.stderr
        val startIdx = stderr.indexOf(SEARCH_START)
        val endIdx = stderr.indexOf(SEARCH_END)

        if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
            return emptyList()
        }

        val includes = stderr.substring(startIdx + SEARCH_START.length, endIdx).trim()
        val dirs =
            includes
                .split(Regex("[\\n\\r]+"))
                .filter { !it.contains("(") }
                .map { "--extra-arg=-isystem${it.trim()}" }

        logger.debug("Compiler: {} Include dirs: {}", compiler, dirs)
        return dirs
    }
}

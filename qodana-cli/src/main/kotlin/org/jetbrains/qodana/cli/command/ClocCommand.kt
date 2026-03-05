package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

class ClocCommand(
    private val terminal: Terminal,
) : CliktCommand("cloc") {

    override fun help(context: Context) =
        "Calculate lines of code for projects"

    private val projectDirs by option("-i", "--project-dir", help = "Project directory")
        .multiple()
    private val output by option("-o", "--output", help = "Output format")
        .default("tabular")

    override fun run() {
        val dirs = projectDirs.ifEmpty { listOf(".") }
        val stats = mutableMapOf<String, LangStats>()

        for (dir in dirs) {
            val root = Path.of(dir)
            if (!Files.isDirectory(root)) {
                terminal.warn("Directory not found: $dir")
                continue
            }
            Files.walk(root).asSequence()
                .filter { it.isRegularFile() }
                .filter { !it.toString().contains("/.") }
                .forEach { file ->
                    val ext = file.extension.ifEmpty { "unknown" }
                    val lang = EXTENSION_MAP[ext] ?: ext
                    val lines = try {
                        Files.readAllLines(file).size
                    } catch (_: Exception) {
                        0
                    }
                    val s = stats.getOrPut(lang) { LangStats() }
                    s.files++
                    s.lines += lines
                }
        }

        when (output) {
            "json" -> printJson(stats)
            else -> printTabular(stats)
        }
    }

    private fun printTabular(stats: Map<String, LangStats>) {
        terminal.println("─".repeat(60))
        terminal.println(String.format("%-20s %10s %15s", "Language", "Files", "Lines"))
        terminal.println("─".repeat(60))
        for ((lang, s) in stats.entries.sortedByDescending { it.value.lines }) {
            terminal.println(String.format("%-20s %10d %15d", lang, s.files, s.lines))
        }
        terminal.println("─".repeat(60))
        terminal.println(
            String.format(
                "%-20s %10d %15d",
                "Total",
                stats.values.sumOf { it.files },
                stats.values.sumOf { it.lines },
            )
        )
    }

    private fun printJson(stats: Map<String, LangStats>) {
        val entries = stats.entries.sortedByDescending { it.value.lines }.joinToString(",\n") { (lang, s) ->
            """  {"language":"$lang","files":${s.files},"lines":${s.lines}}"""
        }
        terminal.println("[\n$entries\n]")
    }

    private class LangStats(var files: Int = 0, var lines: Int = 0)

    companion object {
        private val EXTENSION_MAP = mapOf(
            "kt" to "Kotlin", "kts" to "Kotlin", "java" to "Java",
            "py" to "Python", "go" to "Go", "rs" to "Rust",
            "js" to "JavaScript", "ts" to "TypeScript", "tsx" to "TypeScript",
            "jsx" to "JavaScript", "c" to "C", "cpp" to "C++", "h" to "C Header",
            "cs" to "C#", "rb" to "Ruby", "php" to "PHP", "swift" to "Swift",
            "xml" to "XML", "json" to "JSON", "yaml" to "YAML", "yml" to "YAML",
            "md" to "Markdown", "html" to "HTML", "css" to "CSS",
            "sh" to "Shell", "bash" to "Shell", "zsh" to "Shell",
            "sql" to "SQL", "gradle" to "Gradle", "toml" to "TOML",
            "properties" to "Properties", "txt" to "Plain Text",
        )
    }
}

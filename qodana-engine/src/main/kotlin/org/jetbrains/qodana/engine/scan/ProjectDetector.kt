package org.jetbrains.qodana.engine.scan

import org.jetbrains.qodana.core.product.Linter
import org.jetbrains.qodana.core.product.Linters
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

object ProjectDetector {
    fun isAndroidProject(projectDir: Path): Boolean {
        if (!projectDir.isDirectory()) return false
        return Files.walk(projectDir).use { stream ->
            stream.anyMatch { it.name == "AndroidManifest.xml" && it.isRegularFile() }
        }
    }

    fun containsUnityProjects(projectDir: Path): Boolean {
        val assets = projectDir.resolve("Assets")
        val projectSettings = projectDir.resolve("ProjectSettings")
        if (!assets.isDirectory() || !projectSettings.isDirectory()) return false

        return Files.walk(projectSettings).use { stream ->
            stream.anyMatch {
                it.isRegularFile() && (it.name == "ProjectVersion.txt" || it.extension == "asset")
            }
        }
    }

    fun containsDotNetProjects(projectDir: Path): Boolean {
        if (!projectDir.isDirectory()) return false
        return Files.walk(projectDir).use { stream ->
            stream.anyMatch {
                it.isRegularFile() && it.extension in setOf("sln", "csproj", "vbproj", "fsproj")
            }
        }
    }

    fun containsDotNetFrameworkProjects(projectDir: Path): Boolean {
        if (!projectDir.isDirectory()) return false
        val csprojFiles =
            Files.walk(projectDir).use { stream ->
                stream
                    .filter { it.isRegularFile() && it.extension == "csproj" }
                    .iterator()
                    .asSequence()
                    .toList()
            }
        return csprojFiles.any { isDotNetFrameworkProject(it) }
    }

    private fun isDotNetFrameworkProject(csproj: Path): Boolean {
        if (!csproj.exists()) return false
        return try {
            csproj.readLines().any { line ->
                val trimmed = line.trim()
                (trimmed.contains("<TargetFramework>") || trimmed.contains("<TargetFrameworkVersion>")) &&
                    (trimmed.contains("net4") || trimmed.contains("v4"))
            }
        } catch (_: Exception) {
            false
        }
    }

    private val SKIP_DIRS = setOf(".idea", ".vscode", ".git", "node_modules", "vendor", "build", "target", "__pycache__")

    private val EXT_TO_LANGUAGE =
        mapOf(
            "java" to "Java",
            "kt" to "Kotlin",
            "kts" to "Kotlin",
            "py" to "Python",
            "go" to "Go",
            "js" to "JavaScript",
            "ts" to "TypeScript",
            "tsx" to "TypeScript",
            "jsx" to "JavaScript",
            "cs" to "C#",
            "fs" to "F#",
            "vb" to "Visual Basic .NET",
            "php" to "PHP",
            "rb" to "Ruby",
            "rs" to "Rust",
            "c" to "C",
            "cpp" to "C++",
            "cc" to "C++",
            "cxx" to "C++",
            "h" to "C",
            "hpp" to "C++",
        )

    fun recognizeLanguages(projectDir: Path): List<String> {
        if (!projectDir.isDirectory()) return emptyList()
        val counts = mutableMapOf<String, Int>()
        Files.walk(projectDir).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { file ->
                    file.toAbsolutePath().none { part -> SKIP_DIRS.contains(part.toString()) }
                }.forEach { file ->
                    val lang = EXT_TO_LANGUAGE[file.extension]
                    if (lang != null) {
                        counts[lang] = (counts[lang] ?: 0) + 1
                    }
                }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key }
    }

    fun detectLinter(projectDir: Path): Linter? {
        // First try .idea/ directory
        val ideaLanguages = readIdeaDir(projectDir)
        val languages = ideaLanguages.ifEmpty { recognizeLanguages(projectDir) }
        if (languages.isEmpty()) return null

        // Find the first language that maps to a linter
        for (lang in languages) {
            val linters = Linters.LANGS_TO_LINTERS[lang]
            if (!linters.isNullOrEmpty()) return linters.first()
        }
        return null
    }

    fun readIdeaDir(projectDir: Path): List<String> {
        val ideaDir = projectDir.resolve(".idea")
        if (!ideaDir.isDirectory()) return emptyList()

        val languages = mutableSetOf<String>()
        Files.walk(ideaDir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "iml" }
                .forEach { iml ->
                    try {
                        val content = iml.toFile().readText()
                        if (content.contains("JAVA_MODULE")) languages.add("Java")
                        if (content.contains("PYTHON_MODULE")) languages.add("Python")
                        if (content.contains("Go")) languages.add("Go")
                        if (content.contains("WEB_MODULE")) languages.add("JavaScript")
                        if (content.contains("RUBY_MODULE")) languages.add("Ruby")
                        if (content.contains("PHP")) languages.add("PHP")
                    } catch (_: Exception) {
                        // skip unreadable files
                    }
                }
        }
        return languages.sorted()
    }
}

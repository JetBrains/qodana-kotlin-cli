package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.qodana.core.model.LogEvent
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.model.ContainerExitStatus
import org.jetbrains.qodana.engine.model.ContainerRunSpec
import org.jetbrains.qodana.engine.port.ContainerEngine
import org.jetbrains.qodana.engine.port.ContainerEngineInfo
import org.jetbrains.qodana.engine.port.EngineType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PullCommandParityTest {
    @Test
    fun `unknown yaml linter fails with explicit error`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("qodana.yaml"),
            """
            version: "1.0"
            linter: totally-unknown-linter
            """.trimIndent(),
        )

        val terminal = PullRecordingTerminal()
        val container = RecordingContainerEngine()

        val error =
            assertFailsWith<ProgramResult> {
                PullCommand(container, terminal).parse(listOf("-i", dir.toString()))
            }

        assertEquals(1, error.statusCode)
        assertTrue(terminal.errors.any { it.contains("Unknown linter 'totally-unknown-linter'") })
        assertTrue(container.pulledImages.isEmpty(), "Pull must not be attempted for unknown yaml linter")
    }

    @Test
    fun `withinDocker false with named yaml linter skips pull as native`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("qodana.yaml"),
            """
            version: "1.0"
            linter: qodana-jvm
            withinDocker: false
            """.trimIndent(),
        )

        val terminal = PullRecordingTerminal()
        val container = RecordingContainerEngine()

        PullCommand(container, terminal).parse(listOf("-i", dir.toString()))

        assertTrue(terminal.lines.any { it.contains("Native mode is used, skipping pull") })
        assertTrue(container.pulledImages.isEmpty(), "Pull must be skipped in native mode")
    }

    @Test
    fun `withinDocker false with legacy image-as-linter still pulls docker image`(
        @TempDir dir: Path,
    ) {
        val image = "jetbrains/qodana-jvm:2025.3"
        Files.writeString(
            dir.resolve("qodana.yaml"),
            """
            version: "1.0"
            linter: $image
            withinDocker: false
            """.trimIndent(),
        )

        val terminal = PullRecordingTerminal()
        val container = RecordingContainerEngine()

        PullCommand(container, terminal).parse(listOf("-i", dir.toString()))

        assertEquals(listOf(image), container.pulledImages)
    }
}

private class RecordingContainerEngine : ContainerEngine {
    val pulledImages = mutableListOf<String>()

    override suspend fun pull(
        image: String,
        onProgress: (String) -> Unit,
    ) {
        pulledImages += image
        onProgress("pulled $image")
    }

    override suspend fun create(spec: ContainerRunSpec): String = error("Not used in test")

    override suspend fun start(containerId: String) = error("Not used in test")

    override fun logs(containerId: String) = emptyFlow<LogEvent>()

    override suspend fun wait(containerId: String) = ContainerExitStatus(exitCode = 0)

    override suspend fun remove(
        containerId: String,
        force: Boolean,
    ) = Unit

    override suspend fun info() = ContainerEngineInfo(EngineType.DOCKER, "test", null)

    override suspend fun imageExists(image: String) = false
}

private class PullRecordingTerminal : Terminal {
    val lines = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun print(message: String) {
        lines.add(message)
    }

    override fun println(message: String) {
        lines.add(message)
    }

    override fun error(message: String) {
        errors.add(message)
    }

    override fun info(message: String) {
        lines.add(message)
    }

    override fun warn(message: String) {
        lines.add(message)
    }

    override fun debug(message: String) {
        lines.add(message)
    }

    override fun <T> spinner(
        message: String,
        action: () -> T,
    ): T = action()

    override fun prompt(
        message: String,
        default: String?,
    ): String = default ?: ""

    override fun select(
        message: String,
        choices: List<String>,
    ): String = choices.first()

    override val isInteractive = false
    override var isCi = false

    override fun setRedactedTokens(tokens: Set<String>) {}
}

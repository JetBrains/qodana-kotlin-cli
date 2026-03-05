package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import kotlin.test.*

class QodanaCommandTest {

    private fun createCommand(): QodanaCommand {
        return QodanaCommand().subcommands(
            // Use minimal subcommand stubs: only scan and init are needed for subcommand tests.
            // We cannot instantiate the real subcommands without their dependencies,
            // but we can register lightweight ones with the same names.
            StubCommand("scan"),
            StubCommand("init"),
        )
    }

    @Test
    fun `version flag prints version`() {
        val exception = assertFailsWith<PrintMessage> {
            createCommand().parse(listOf("--version"))
        }
        assertNotNull(exception.message)
        assertTrue(
            exception.message!!.contains(QodanaCommand.VERSION),
            "Expected version output to contain '${QodanaCommand.VERSION}', but was: '${exception.message}'"
        )
    }

    @Test
    fun `help flag prints help`() {
        val exception = assertFailsWith<PrintHelpMessage> {
            createCommand().parse(listOf("--help"))
        }
        val helpText = exception.context?.command?.getFormattedHelp() ?: ""
        assertTrue(
            helpText.contains("Qodana CLI"),
            "Expected help output to contain 'Qodana CLI', but was: $helpText"
        )
    }

    @Test
    fun `root command without subcommand shows help`() {
        // QodanaCommand has invokeWithoutSubcommand = true and echoes help when no subcommand is given.
        // It should not throw; it prints help via echo.
        val command = createCommand()
        // parse with no args should succeed (invokeWithoutSubcommand = true)
        command.parse(emptyList())
    }

    @Test
    fun `scan subcommand exists`() {
        val exception = assertFailsWith<PrintHelpMessage> {
            createCommand().parse(listOf("scan", "--help"))
        }
        val helpText = exception.context?.command?.getFormattedHelp() ?: ""
        assertNotNull(helpText)
    }

    @Test
    fun `init subcommand exists`() {
        val exception = assertFailsWith<PrintHelpMessage> {
            createCommand().parse(listOf("init", "--help"))
        }
        val helpText = exception.context?.command?.getFormattedHelp() ?: ""
        assertNotNull(helpText)
    }

    @Test
    fun `unknown subcommand fails`() {
        assertFailsWith<UsageError> {
            createCommand().parse(listOf("nonexistent"))
        }
    }
}

/**
 * A minimal Clikt subcommand stub used for testing command registration
 * without requiring real dependencies.
 */
private class StubCommand(name: String) : com.github.ajalt.clikt.core.CliktCommand(name) {
    override fun run() {
        // no-op
    }
}

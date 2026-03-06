package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.sarif.QodanaSarifService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class ViewCommandParityTest {

    @Test
    fun `view counts only new-or-empty baseline states and skips unchanged from printing`(@TempDir dir: Path) {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "Qodana"}},
                "results": [
                  {
                    "ruleId": "R1",
                    "message": {"text": "issue 1"},
                    "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/A.kt"}, "region": {"startLine": 10}}}]
                  },
                  {
                    "ruleId": "R2",
                    "baselineState": "new",
                    "message": {"text": "issue 2"},
                    "locations": []
                  },
                  {
                    "ruleId": "R3",
                    "baselineState": "unchanged",
                    "message": {"text": "issue 3"},
                    "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/B.kt"}, "region": {"startLine": 20}}}]
                  }
                ]
              }]
            }
        """.trimIndent()
        val sarifPath = dir.resolve("report.sarif.json")
        Files.writeString(sarifPath, sarif)

        val terminal = ViewRecordingTerminal()
        ViewCommand(QodanaSarifService(), terminal).parse(listOf("-f", sarifPath.toString()))

        val rendered = terminal.lines.joinToString("\n")
        assertTrue(rendered.contains("R1: issue 1 (src/A.kt:10)"))
        assertTrue(!rendered.contains("R3: issue 3"))
        assertTrue(rendered.contains("Found 2 new problems according to the checks applied"))
    }

    @Test
    fun `view prints zero-new-problems message when all results are unchanged`(@TempDir dir: Path) {
        val sarif = """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": {"driver": {"name": "Qodana"}},
                "results": [
                  {
                    "ruleId": "R1",
                    "baselineState": "unchanged",
                    "message": {"text": "issue"},
                    "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/A.kt"}, "region": {"startLine": 1}}}]
                  }
                ]
              }]
            }
        """.trimIndent()
        val sarifPath = dir.resolve("report.sarif.json")
        Files.writeString(sarifPath, sarif)

        val terminal = ViewRecordingTerminal()
        ViewCommand(QodanaSarifService(), terminal).parse(listOf("-f", sarifPath.toString()))

        val rendered = terminal.lines.joinToString("\n")
        assertTrue(rendered.contains("No new problems found according to the checks applied"))
    }
}

private class ViewRecordingTerminal : Terminal {
    val lines = mutableListOf<String>()

    override fun print(message: String) {
        lines.add(message)
    }

    override fun println(message: String) {
        lines.add(message)
    }

    override fun error(message: String) {
        lines.add(message)
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

    override fun <T> spinner(message: String, action: () -> T): T = action()
    override fun prompt(message: String, default: String?): String = default ?: ""
    override fun select(message: String, choices: List<String>): String = choices.first()
    override val isInteractive = false
    override var isCi = false
    override fun setRedactedTokens(tokens: Set<String>) {}
}

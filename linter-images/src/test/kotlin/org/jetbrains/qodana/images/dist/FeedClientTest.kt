package org.jetbrains.qodana.images.dist

import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.FakeCommandRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedClientTest {
    private val feedJson =
        """
        {"Code":"QDJVM","Releases":[
          {"Date":"2025-09-01","Type":"release","Version":"2025.3.1","MajorVersion":"2025.3","Build":"253.1234.56",
           "Downloads":{"linux":{"Link":"L","ChecksumLink":"L.sha256","Size":1}}}
        ]}
        """.trimIndent()

    /** A FakeCommandRunner whose curl writes [feedJson] to the `-o <path>` target and records the argv. */
    private fun curlRunner() =
        FakeCommandRunner().apply {
            on({ it.contains("curl") }) { argv ->
                Files.writeString(Path.of(argv[argv.indexOf("-o") + 1]), feedJson)
                CommandResult(0, "", "")
            }
        }

    @Test
    fun `fetch builds slug URL parses object and adds bearer header when token present`() {
        val runner = curlRunner()
        val feed =
            FeedClient(runner).fetch(
                feedUrl = "https://download.jetbrains.com/qodana/feed",
                linterSlug = "qodana-jvm",
                token = "secret",
            )

        val curl = runner.invocations.single { it.contains("curl") }
        assertTrue(
            curl.any { it == "https://download.jetbrains.com/qodana/feed/qodana-jvm.releases.json" },
            curl.toString(),
        )
        assertTrue(curl.any { it.contains("secret") }, "bearer header must carry the token: $curl")
        assertEquals("QDJVM", feed.code)
        assertEquals("253.1234.56", feed.releases.single().build)
    }

    @Test
    fun `fetch tolerates trailing slash and omits the bearer header when token is null`() {
        val runner = curlRunner()
        val feed =
            FeedClient(runner).fetch(
                feedUrl = "https://download.jetbrains.com/qodana/feed/",
                linterSlug = "qodana-jvm",
                token = null,
            )

        val curl = runner.invocations.single { it.contains("curl") }
        assertTrue(
            curl.any { it == "https://download.jetbrains.com/qodana/feed/qodana-jvm.releases.json" },
            curl.toString(),
        )
        assertTrue(curl.none { it.equals("--header", ignoreCase = true) }, "no bearer header when token is null: $curl")
        assertEquals("QDJVM", feed.code)
    }
}

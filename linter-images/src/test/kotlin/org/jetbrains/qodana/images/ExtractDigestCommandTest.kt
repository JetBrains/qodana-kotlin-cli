package org.jetbrains.qodana.images

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ExtractDigestCommandTest {
    private val sha = "sha256:" + "a".repeat(64)

    @Test
    fun `the --file CLI path reads via getContent and echoes just the digest`() {
        // Exercises the production entry point (the --file option + getContent + echo), which the
        // workflow captures as `$(image-tool extract-digest --file push.log)`.
        val cmd = ExtractDigestCommand(getContent = { "5f70bf18: Pushed\nfoo: digest: $sha size: 9" })
        assertEquals(sha, cmd.test("--file push.log").stdout.trim())
    }

    @Test
    fun `extracts the single digest from a real-looking push log`() {
        val log =
            """
            The push refers to repository [registry.jetbrains.team/p/sa/qodana-kcli-images/qodana-jvm]
            5f70bf18a086: Pushed
            _staging.42.1-amd64: digest: $sha size: 1782
            """.trimIndent()
        assertEquals(sha, ExtractDigestCommand.extract(log))
    }

    @Test
    fun `no digest fails loud`() {
        val log = "The push refers to ...\n5f70bf18a086: Pushed"
        val ex = runCatching { ExtractDigestCommand.extract(log) }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("found 0"), "was $ex")
    }

    @Test
    fun `ambiguous multiple digests fail loud`() {
        val other = "sha256:" + "b".repeat(64)
        val log = "x: digest: $sha size: 1\ny: digest: $other size: 2"
        val ex = runCatching { ExtractDigestCommand.extract(log) }.exceptionOrNull()
        assertTrue(ex is IllegalStateException && ex.message!!.contains("found 2"), "was $ex")
    }
}

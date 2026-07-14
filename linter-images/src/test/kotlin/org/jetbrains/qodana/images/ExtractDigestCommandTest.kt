package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ExtractDigestCommandTest {
    private val sha = "sha256:" + "a".repeat(64)

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

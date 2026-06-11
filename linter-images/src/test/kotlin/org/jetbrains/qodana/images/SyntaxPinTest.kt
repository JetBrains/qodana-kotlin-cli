package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the single-source dockerfile-x frontend pin.
 *
 * There is no lib/syntax.dockerfile (the `# syntax=` directive must be the literal first line of
 * each thin image, so it cannot be INCLUDEd). The canonical line is recorded in
 * docs/phase-0-decisions.md under the ONE key DOCKERFILE_X_SYNTAX (the FULL `# syntax=...@sha256:`
 * line); every thin image's first line must be byte-identical to it.
 */
class SyntaxPinTest {
    private val docs: Path = Path.of("docs/phase-0-decisions.md")
    private val imagesDir: Path = Path.of("docker/images")

    /** The single source of truth: the `DOCKERFILE_X_SYNTAX: ` row in the decisions file. */
    private val pinnedSyntaxLine: String by lazy {
        Files
            .readAllLines(docs)
            .firstOrNull { it.startsWith("DOCKERFILE_X_SYNTAX: ") }
            ?.removePrefix("DOCKERFILE_X_SYNTAX: ")
            ?.trim()
            ?: error("docs/phase-0-decisions.md must contain a 'DOCKERFILE_X_SYNTAX: ' row (Phase-0 Spike A)")
    }

    @Test
    fun `pinned syntax line targets dockerfile-x 1_6_0 by digest`() {
        val line = pinnedSyntaxLine
        assertTrue(
            line.startsWith("# syntax=docker.io/devthefuture/dockerfile-x:1.6.0@sha256:"),
            "DOCKERFILE_X_SYNTAX must pin dockerfile-x 1.6.0 by digest, was: '$line'",
        )
        val digest = line.substringAfter("@sha256:")
        assertTrue(
            digest.matches(Regex("[0-9a-f]{64}")),
            "digest must be a 64-char sha256 hex, was: '$digest'",
        )
    }

    @Test
    fun `every thin image first line equals the pinned syntax line`() {
        val images =
            Files.list(imagesDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".dockerfile") }.sorted().toList()
            }
        assertTrue(images.isNotEmpty(), "expected at least one thin image under docker/images")
        images.forEach { img ->
            val firstLine = Files.readAllLines(img).first()
            assertTrue(
                firstLine == pinnedSyntaxLine,
                "${img.fileName} first line must equal the pinned syntax line.\n" +
                    "  expected: '$pinnedSyntaxLine'\n  actual:   '$firstLine'",
            )
        }
    }
}

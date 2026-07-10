package org.jetbrains.qodana.images.docker

import org.jetbrains.qodana.images.EnvContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DebianBasesTest {
    private val file: Path = Path.of("docker/debian-bases.txt")

    private fun rows(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        Files
            .readAllLines(file)
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val parts = line.split(Regex("\\s+"))
                assertEquals(2, parts.size, "each row is '<codename> <digest>', was: '$line'")
                assertEquals(null, map.put(parts[0], parts[1]), "duplicate codename ${parts[0]}")
            }
        return map
    }

    @Test
    fun `has exactly bookworm and trixie rows with dhi digests`() {
        val rows = rows()
        assertEquals(setOf("bookworm", "trixie"), rows.keys)
        rows.forEach { (os, digest) ->
            assert(digest.startsWith("dhi.io/debian-base:") && "@sha256:" in digest) {
                "$os digest must be a pinned dhi.io/debian-base ref: $digest"
            }
        }
    }

    @Test
    fun `rows match the phase-0-decisions base pins (no drift)`() {
        val rows = rows()
        assertEquals(EnvContract.pin("QD_BASE_IMAGE"), rows["bookworm"], "bookworm row must equal phase-0 pin")
        assertEquals(EnvContract.pin("QD_TRIXIE_BASE_IMAGE"), rows["trixie"], "trixie row must equal phase-0 pin")
    }
}

package org.jetbrains.qodana.images.dist

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.GZIPOutputStream
import kotlin.io.path.isExecutable
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TarGzExtractorTest {
    /**
     * Writes a `.tar.gz` whose entries are exactly [entries] (path -> contents). Directory entries
     * are inferred from the slashes in each path; mirrors the real qodana dist that nests everything
     * under a single top-level directory.
     */
    private fun writeTarGz(
        archive: Path,
        entries: Map<String, String>,
    ) {
        TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(archive)))).use { tar ->
            for ((path, content) in entries) {
                val bytes = content.toByteArray()
                val entry = TarArchiveEntry(path)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    /** A single tar entry to write via [writeTarEntries]. */
    private sealed interface Entry {
        val name: String

        data class File(
            override val name: String,
            val content: String,
            val mode: Int? = null,
        ) : Entry

        data class Dir(
            override val name: String,
            val mode: Int? = null,
        ) : Entry

        data class Symlink(
            override val name: String,
            val target: String,
        ) : Entry
    }

    /** Writes a `.tar.gz` with exactly [entries], in order, supporting files, dirs, modes, and symlinks. */
    private fun writeTarEntries(
        archive: Path,
        entries: List<Entry>,
    ) {
        TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(archive)))).use { tar ->
            for (entry in entries) {
                when (entry) {
                    is Entry.File -> {
                        val bytes = entry.content.toByteArray()
                        val tarEntry = TarArchiveEntry(entry.name)
                        entry.mode?.let { tarEntry.mode = it }
                        tarEntry.size = bytes.size.toLong()
                        tar.putArchiveEntry(tarEntry)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                    is Entry.Dir -> {
                        val dirName = if (entry.name.endsWith("/")) entry.name else "${entry.name}/"
                        val tarEntry = TarArchiveEntry(dirName)
                        entry.mode?.let { tarEntry.mode = it }
                        tar.putArchiveEntry(tarEntry)
                        tar.closeArchiveEntry()
                    }
                    is Entry.Symlink -> {
                        val tarEntry = TarArchiveEntry(entry.name, TarArchiveEntry.LF_SYMLINK)
                        tarEntry.linkName = entry.target
                        tar.putArchiveEntry(tarEntry)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
    }

    @Test
    fun `flattens the single top-level dir so target is the IDE root`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("qodana-QDJVM-253.200.tar.gz")
        writeTarGz(
            archive,
            mapOf(
                "qodana-QDJVM-253.200/product-info.json" to """{"productCode":"IU"}""",
                "qodana-QDJVM-253.200/bin/idea.sh" to "#!/bin/sh\n",
                "qodana-QDJVM-253.200/lib/app.jar" to "JAR",
            ),
        )
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        // target IS the IDE root: product-info.json sits directly under it, not under a nested dir.
        val productInfo = target.resolve("product-info.json")
        assertTrue(Files.exists(productInfo), "product-info.json must be directly under target")
        assertEquals("""{"productCode":"IU"}""", productInfo.readText())
        assertTrue(Files.exists(target.resolve("bin/idea.sh")))
        assertTrue(Files.exists(target.resolve("lib/app.jar")))
        // No leftover wrapper directory under target.
        assertTrue(Files.notExists(target.resolve("qodana-QDJVM-253.200")))
    }

    @Test
    fun `does not flatten when there is no single top-level dir`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("flat.tar.gz")
        writeTarGz(
            archive,
            mapOf(
                "product-info.json" to """{"productCode":"IU"}""",
                "bin/idea.sh" to "#!/bin/sh\n",
            ),
        )
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        assertTrue(Files.exists(target.resolve("product-info.json")))
        assertTrue(Files.exists(target.resolve("bin/idea.sh")))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // executable bit + symlinks are POSIX; the IDE images run on Linux.
    fun `preserves executable bit and symlinks`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("qodana-QDJVM-253.200.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(archive)))).use { tar ->
            // Executable launcher (mode 0755).
            val launcherBytes = "#!/bin/sh\n".toByteArray()
            val launcher = TarArchiveEntry("qodana-QDJVM-253.200/bin/idea.sh")
            launcher.mode = "755".toInt(8)
            launcher.size = launcherBytes.size.toLong()
            tar.putArchiveEntry(launcher)
            tar.write(launcherBytes)
            tar.closeArchiveEntry()

            // A real symlink entry pointing at the launcher.
            val link = TarArchiveEntry("qodana-QDJVM-253.200/bin/idea", TarArchiveEntry.LF_SYMLINK)
            link.linkName = "idea.sh"
            tar.putArchiveEntry(link)
            tar.closeArchiveEntry()
        }
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        val launcherOut = target.resolve("bin/idea.sh")
        assertTrue(launcherOut.isExecutable(), "extracted launcher must keep its executable bit")

        val symlinkOut = target.resolve("bin/idea")
        assertTrue(Files.isSymbolicLink(symlinkOut), "symlink entry must be extracted as a real symlink")
        assertEquals("idea.sh", symlinkOut.readSymbolicLink().toString())
    }

    @Test
    fun `fails loudly when the archive is missing`(
        @TempDir tmp: Path,
    ) {
        assertFailsWith<Exception> {
            TarGzExtractor().extractFlattened(tmp.resolve("nope.tar.gz"), tmp.resolve("staging"))
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks are POSIX; the IDE images run on Linux.
    fun `preserves an internal relative symlink that points elsewhere in the tree`(
        @TempDir tmp: Path,
    ) {
        // A symlink whose target sits in a sibling subtree (../lib/native.so), not next to the link,
        // must still be recreated verbatim: legitimate dist symlinks span directories.
        val archive = tmp.resolve("dist.tar.gz")
        writeTarEntries(
            archive,
            listOf(
                Entry.File("dist/lib/native.so", "ELF"),
                Entry.Symlink("dist/bin/native.so", "../lib/native.so"),
            ),
        )
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        val link = target.resolve("bin/native.so")
        assertTrue(Files.isSymbolicLink(link), "internal relative symlink must survive extraction")
        assertEquals("../lib/native.so", link.readSymbolicLink().toString())
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks are POSIX; the IDE images run on Linux.
    fun `rejects a symlink whose target is an absolute path`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("evil.tar.gz")
        writeTarEntries(
            archive,
            listOf(Entry.Symlink("dist/passwd", "/etc/passwd")),
        )
        val target = tmp.resolve("staging")

        assertFailsWith<IllegalArgumentException> {
            TarGzExtractor().extractFlattened(archive, target)
        }
        assertTrue(Files.notExists(target.resolve("passwd")), "no escaping symlink may be created")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks are POSIX; the IDE images run on Linux.
    fun `rejects a symlink whose target escapes the target dir with dot-dot`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("evil.tar.gz")
        writeTarEntries(
            archive,
            listOf(Entry.Symlink("dist/escape", "../../escape")),
        )
        val target = tmp.resolve("staging")

        assertFailsWith<IllegalArgumentException> {
            TarGzExtractor().extractFlattened(archive, target)
        }
        assertTrue(Files.notExists(target.resolve("escape")), "no escaping symlink may be created")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks are POSIX; the IDE images run on Linux.
    fun `rejects a planted dir-symlink that would let a later entry write outside the target`(
        @TempDir tmp: Path,
    ) {
        // The classic write-through-symlink escape: plant `evil -> <outside>`, then a file `evil/payload`.
        // Validating the symlink target rejects `evil` up front, so the payload is never written outside.
        val outside = tmp.resolve("outside")
        Files.createDirectories(outside)
        val archive = tmp.resolve("evil.tar.gz")
        writeTarEntries(
            archive,
            listOf(
                Entry.Symlink("dist/evil", outside.toAbsolutePath().toString()),
                Entry.File("dist/evil/payload", "PWNED"),
            ),
        )
        val target = tmp.resolve("staging")

        assertFailsWith<IllegalArgumentException> {
            TarGzExtractor().extractFlattened(archive, target)
        }
        assertTrue(Files.notExists(outside.resolve("payload")), "payload must NOT be written outside the target")
    }

    @Test
    fun `rejects a plain entry whose name escapes with dot-dot`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("evil.tar.gz")
        writeTarEntries(
            archive,
            listOf(Entry.File("../escape.txt", "PWNED")),
        )
        val target = tmp.resolve("staging")

        assertFailsWith<IllegalArgumentException> {
            TarGzExtractor().extractFlattened(archive, target)
        }
        assertTrue(Files.notExists(tmp.resolve("escape.txt")), "escaping file must NOT be written outside the target")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // builds a POSIX absolute entry name; the IDE images run on Linux.
    fun `does not write a plain entry whose name is an absolute path at that absolute path`(
        @TempDir tmp: Path,
    ) {
        // commons-compress strips the leading slash from entry names, so an absolute name can never
        // reach the absolute victim path. This pins that defang so the guard is never weakened into
        // trusting raw absolute names. (A sibling file keeps the layout from flattening the victim's
        // first path segment away, but the security invariant asserted here is the absolute path.)
        val archive = tmp.resolve("evil.tar.gz")
        val absVictim = tmp.resolve("abs-escape.txt").toAbsolutePath()
        writeTarEntries(
            archive,
            listOf(
                Entry.File(absVictim.toString(), "PWNED"),
                Entry.File("safe.txt", "ok"),
            ),
        )
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        assertTrue(Files.notExists(absVictim), "absolute-path entry must NOT be written at the absolute victim path")
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX directory modes; the IDE images run on Linux.
    fun `applies a read-only directory mode after writing its children`(
        @TempDir tmp: Path,
    ) {
        // A legal tar may list a 0555 (non-writable) directory BEFORE its file children. Applying the
        // mode eagerly would make the child write fail with AccessDeniedException, so it must be deferred.
        val archive = tmp.resolve("dist.tar.gz")
        writeTarEntries(
            archive,
            listOf(
                Entry.Dir("dist/data", mode = "555".toInt(8)),
                Entry.File("dist/data/child.txt", "hello", mode = "644".toInt(8)),
            ),
        )
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        val dir = target.resolve("data")
        val child = dir.resolve("child.txt")
        assertTrue(Files.exists(child), "child must be written despite the read-only parent dir mode")
        assertEquals("hello", child.readText())
        assertEquals(
            "r-xr-xr-x",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)),
            "final directory mode must be exactly 0555",
        )
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX directory modes; the IDE images run on Linux.
    fun `applies a non-traversable parent dir mode after its child dir mode`(
        @TempDir tmp: Path,
    ) {
        // A parent dir whose final mode drops the traverse (x) bit, listed BEFORE its child dir. Applying
        // modes in archive order would chmod the parent first, then fail to chmod the now-unreachable
        // child with AccessDeniedException. Applying deepest-first avoids that.
        val archive = tmp.resolve("dist.tar.gz")
        writeTarEntries(
            archive,
            listOf(
                Entry.Dir("dist/a", mode = "600".toInt(8)),
                Entry.Dir("dist/a/b", mode = "755".toInt(8)),
                Entry.File("dist/a/b/c.txt", "hi"),
            ),
        )
        val target = tmp.resolve("staging")

        TarGzExtractor().extractFlattened(archive, target)

        val parent = target.resolve("a")
        assertEquals(
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(parent)),
            "final parent directory mode must be exactly 0600",
        )
    }
}

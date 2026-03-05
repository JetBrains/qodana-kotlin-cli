package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.product.Analyzer
import org.jetbrains.qodana.core.product.IntellijLinterProperties
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.port.HttpResponse
import org.jetbrains.qodana.engine.port.HttpTransport
import org.jetbrains.qodana.engine.port.MultipartPart
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeInstallerTest {

    private val sampleFeedJson = """
        [
          {
            "Code": "IIU",
            "Releases": [
              {
                "Date": "2025-03-01",
                "Type": "release",
                "Version": "2025.3.1",
                "MajorVersion": "2025.3",
                "Build": "253.1234",
                "Downloads": {
                  "linux": {"Link": "https://example.com/idea-2025.3.1.tar.gz", "Size": 1000, "ChecksumLink": ""},
                  "linuxARM64": {"Link": "https://example.com/idea-2025.3.1-aarch64.tar.gz", "Size": 1000, "ChecksumLink": ""},
                  "macSit": {"Link": "https://example.com/idea-2025.3.1.sit", "Size": 1000, "ChecksumLink": ""},
                  "macSitM1": {"Link": "https://example.com/idea-2025.3.1-aarch64.sit", "Size": 1000, "ChecksumLink": ""},
                  "windowsZip": {"Link": "https://example.com/idea-2025.3.1.win.zip", "Size": 1000, "ChecksumLink": ""}
                }
              },
              {
                "Date": "2025-02-15",
                "Type": "release",
                "Version": "2025.3.0",
                "MajorVersion": "2025.3",
                "Build": "253.1000",
                "Downloads": {
                  "linux": {"Link": "https://example.com/idea-2025.3.0.tar.gz", "Size": 900, "ChecksumLink": ""}
                }
              },
              {
                "Date": "2025-03-10",
                "Type": "eap",
                "Version": "2025.3.2-EAP",
                "MajorVersion": "2025.3",
                "Build": "253.2000",
                "Downloads": {
                  "linux": {"Link": "https://example.com/idea-2025.3.2-eap.tar.gz", "Size": 1100, "ChecksumLink": ""}
                }
              }
            ]
          },
          {
            "Code": "GO",
            "Releases": [
              {
                "Date": "2025-03-01",
                "Type": "release",
                "Version": "2025.3.1",
                "MajorVersion": "2025.3",
                "Downloads": {
                  "linux": {"Link": "https://example.com/goland-2025.3.1.tar.gz", "Size": 800, "ChecksumLink": ""}
                }
              }
            ]
          }
        ]
    """.trimIndent()

    private fun fakeHttp(feedJson: String = sampleFeedJson) = object : HttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>) =
            HttpResponse(200, feedJson)
        override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>) =
            HttpResponse(200, "")
        override suspend fun download(url: String, target: Path, headers: Map<String, String>) {}
        override suspend fun uploadMultipart(url: String, parts: List<MultipartPart>, headers: Map<String, String>) =
            HttpResponse(200, "")
    }

    private fun fakeFs() = object : FileSystem {
        override fun read(path: Path) = ""
        override fun readBytes(path: Path) = byteArrayOf()
        override fun write(path: Path, content: String) {}
        override fun writeBytes(path: Path, content: ByteArray) {}
        override fun copy(source: Path, target: Path) {}
        override fun walk(root: Path, glob: String?) = emptySequence<Path>()
        override fun exists(path: Path) = false
        override fun createDirectories(path: Path): Path = path
        override fun tempDir(prefix: String) = Path.of("/tmp/$prefix")
        override fun delete(path: Path) {}
        override fun extractArchive(archive: Path, target: Path) {}
    }

    private fun fakeTerminal() = object : Terminal {
        val errors = mutableListOf<String>()
        override fun print(message: String) {}
        override fun println(message: String) {}
        override fun error(message: String) { errors.add(message) }
        override fun info(message: String) {}
        override fun warn(message: String) {}
        override fun debug(message: String) {}
        override fun <T> spinner(message: String, action: () -> T): T = action()
        override fun prompt(message: String, default: String?): String = default ?: ""
        override fun select(message: String, choices: List<String>): String = choices.first()
        override val isInteractive: Boolean = false
        override var isCi: Boolean = false
        override fun setRedactedTokens(tokens: Set<String>) {}
    }

    @Test
    fun `getProductByCode returns matching product`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        val product = installer.getProductByCode("IIU")
        assertNotNull(product)
        assertEquals("IIU", product.code)
        assertEquals(3, product.releases.size)
    }

    @Test
    fun `getProductByCode returns null for unknown code`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        val product = installer.getProductByCode("NONEXISTENT")
        assertNull(product)
    }

    @Test
    fun `selectLatestCompatibleRelease picks latest by date`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        val product = installer.getProductByCode("IIU")!!

        val release = installer.selectLatestCompatibleRelease(product, "release")
        assertNotNull(release)
        assertEquals("2025.3.1", release.version)
        assertEquals("2025-03-01", release.date)
    }

    @Test
    fun `selectLatestCompatibleRelease filters by type`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        val product = installer.getProductByCode("IIU")!!

        val eapRelease = installer.selectLatestCompatibleRelease(product, "eap")
        assertNotNull(eapRelease)
        assertEquals("2025.3.2-EAP", eapRelease.version)
    }

    @Test
    fun `selectLatestCompatibleRelease returns null when no match`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        val product = installer.getProductByCode("GO")!!

        val eapRelease = installer.selectLatestCompatibleRelease(product, "eap")
        assertNull(eapRelease)
    }

    @Test
    fun `getIde returns null for non-native linter`() {
        val terminal = fakeTerminal()
        val installer = IdeInstaller(fakeHttp(), fakeFs(), terminal)
        val analyzer = Analyzer.Native(Linters.ANDROID, isEap = false)

        val result = installer.getIde(analyzer)
        assertNull(result)
        assertTrue(terminal.errors.any { "not supported" in it })
    }

    @Test
    fun `getIde returns download info for supported linter`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        val analyzer = Analyzer.Native(Linters.JVM, isEap = false)

        val result = installer.getIde(analyzer)
        // Result depends on current OS/arch, but should not be null for a valid product
        // The test feed has downloads for all platforms
        assertNotNull(result)
        assertTrue(result.link.contains("example.com"))
    }

    @Test
    fun `getPluginsUrl replaces sit extension`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        val result = installer.getPluginsUrl("https://example.com/idea-2025.3.1-aarch64.sit")
        assertEquals("https://example.com/idea-2025.3.1.sit".replace(".sit", "-custom-plugins.zip"),
            installer.getPluginsUrl("https://example.com/idea-2025.3.1.sit"))
    }

    @Test
    fun `getPluginsUrl replaces win zip extension`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        assertEquals(
            "https://example.com/idea-2025.3.1-custom-plugins.zip",
            installer.getPluginsUrl("https://example.com/idea-2025.3.1.win.zip"),
        )
    }

    @Test
    fun `getPluginsUrl replaces tar gz extension`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        assertEquals(
            "https://example.com/idea-2025.3.1-custom-plugins.zip",
            installer.getPluginsUrl("https://example.com/idea-2025.3.1.tar.gz"),
        )
    }

    @Test
    fun `getPluginsUrl strips aarch64 from url`() {
        val installer = IdeInstaller(fakeHttp(), fakeFs(), fakeTerminal())
        val result = installer.getPluginsUrl("https://example.com/idea-2025.3.1-aarch64.tar.gz")
        assertEquals("https://example.com/idea-2025.3.1-custom-plugins.zip", result)
    }

    @Test
    fun `verifySha256 throws on mismatch`(@TempDir tmpDir: Path) {
        val filePath = tmpDir.resolve("test.bin")
        Files.write(filePath, "hello world".toByteArray())

        val checksumFile = tmpDir.resolve("test.bin.sha256")
        // Write a wrong checksum
        val wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000"

        val http = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, "")
            override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>) = HttpResponse(200, "")
            override suspend fun download(url: String, target: Path, headers: Map<String, String>) {
                Files.writeString(target, "$wrongChecksum  test.bin")
            }
            override suspend fun uploadMultipart(url: String, parts: List<MultipartPart>, headers: Map<String, String>) = HttpResponse(200, "")
        }

        val fs = object : FileSystem {
            override fun read(path: Path) = Files.readString(path)
            override fun readBytes(path: Path) = Files.readAllBytes(path)
            override fun write(path: Path, content: String) { Files.writeString(path, content) }
            override fun writeBytes(path: Path, content: ByteArray) { Files.write(path, content) }
            override fun copy(source: Path, target: Path) {}
            override fun walk(root: Path, glob: String?) = emptySequence<Path>()
            override fun exists(path: Path) = Files.exists(path)
            override fun createDirectories(path: Path): Path = Files.createDirectories(path)
            override fun tempDir(prefix: String) = Files.createTempDirectory(prefix)
            override fun delete(path: Path) { Files.deleteIfExists(path) }
            override fun extractArchive(archive: Path, target: Path) {}
        }

        val installer = IdeInstaller(http, fs, fakeTerminal())
        val ex = assertFailsWith<IllegalStateException> {
            kotlinx.coroutines.test.runTest {
                installer.verifySha256("https://example.com/checksum", filePath, tmpDir)
            }
        }
        assertTrue(ex.message!!.contains("Checksums don't match"))
    }

    @Test
    fun `verifySha256 passes on correct checksum`(@TempDir tmpDir: Path) {
        val content = "hello world"
        val filePath = tmpDir.resolve("test.bin")
        Files.write(filePath, content.toByteArray())

        // Compute correct SHA-256
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val correctChecksum = digest.digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val http = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, "")
            override suspend fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>) = HttpResponse(200, "")
            override suspend fun download(url: String, target: Path, headers: Map<String, String>) {
                Files.writeString(target, "$correctChecksum  test.bin")
            }
            override suspend fun uploadMultipart(url: String, parts: List<MultipartPart>, headers: Map<String, String>) = HttpResponse(200, "")
        }

        val fs = object : FileSystem {
            override fun read(path: Path) = Files.readString(path)
            override fun readBytes(path: Path) = Files.readAllBytes(path)
            override fun write(path: Path, content: String) { Files.writeString(path, content) }
            override fun writeBytes(path: Path, content: ByteArray) { Files.write(path, content) }
            override fun copy(source: Path, target: Path) {}
            override fun walk(root: Path, glob: String?) = emptySequence<Path>()
            override fun exists(path: Path) = Files.exists(path)
            override fun createDirectories(path: Path): Path = Files.createDirectories(path)
            override fun tempDir(prefix: String) = Files.createTempDirectory(prefix)
            override fun delete(path: Path) { Files.deleteIfExists(path) }
            override fun extractArchive(archive: Path, target: Path) {}
        }

        val installer = IdeInstaller(http, fs, fakeTerminal())
        // Should not throw
        kotlinx.coroutines.test.runTest {
            installer.verifySha256("https://example.com/checksum", filePath, tmpDir)
        }
    }
}

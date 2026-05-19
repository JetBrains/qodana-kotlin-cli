package org.jetbrains.qodana.engine.startup

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.qodana.core.port.FileSystem
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.core.product.Analyzer
import org.jetbrains.qodana.core.product.IntellijLinterProperties
import org.jetbrains.qodana.core.product.Linters
import org.jetbrains.qodana.engine.port.HttpTransport
import java.nio.file.Path
import java.security.MessageDigest

class IdeInstaller(
    private val httpTransport: HttpTransport,
    private val fileSystem: FileSystem,
    private val terminal: Terminal,
    private val productFeedUrl: String = DEFAULT_PRODUCT_FEED_URL,
) {
    private val mapper = ObjectMapper().registerModule(kotlinModule())

    suspend fun downloadAndInstall(
        analyzer: Analyzer,
        baseDir: Path,
    ): Path {
        val downloadInfo =
            getIde(analyzer)
                ?: error("Error while obtaining the URL for the supplied IDE, exiting")

        val ideUrl = downloadInfo.link
        val fileName = Path.of(ideUrl).fileName.toString()
        val fileExt = fileName.substringAfterLast('.', "")
        val installDir = baseDir.resolve(fileName.removeSuffix(".$fileExt"))

        if (fileSystem.exists(installDir)) {
            terminal.debug("IDE already installed to $installDir, skipping download")
            return resolveInstallDir(installDir)
        }

        val downloadedPath = baseDir.resolve(fileName)
        terminal.info("Downloading IDE from $ideUrl...")
        httpTransport.download(ideUrl, downloadedPath)

        try {
            if (downloadInfo.checksumLink.isNotEmpty()) {
                verifySha256(downloadInfo.checksumLink, downloadedPath, baseDir)
            }

            extractArchive(downloadedPath, installDir, fileExt)
        } finally {
            try {
                fileSystem.delete(downloadedPath)
            } catch (_: Exception) {
            }
        }

        return resolveInstallDir(installDir)
    }

    fun getIde(analyzer: Analyzer): ReleaseDownloadInfo? {
        val dist = if (analyzer.isEap) Linters.EAP_VER else Linters.RELEASE_VER
        val linter = analyzer.linter

        if (!linter.supportsNative) {
            terminal.error("Native mode for linter ${linter.name} is not supported")
            return null
        }

        val properties = IntellijLinterProperties.findByLinter(linter)
        if (properties == null || properties.feedProductCode.isEmpty()) {
            terminal.error("Native mode for linter ${linter.name} is not supported")
            return null
        }

        val product =
            getProductByCode(properties.feedProductCode) ?: run {
                terminal.error("Product info is not found for code: ${properties.feedProductCode}")
                return null
            }

        val release =
            selectLatestCompatibleRelease(product, dist) ?: run {
                terminal.error("Could not find a $dist version for '${properties.presentableName}'")
                return null
            }

        val downloadType = resolveDownloadType()
        val downloads = release.downloads ?: return null
        return downloads[downloadType] ?: run {
            terminal.error(
                "${properties.feedProductCode} ${release.version} ($dist) " +
                    "is not available or not supported for the current platform",
            )
            null
        }
    }

    fun getProductByCode(code: String): Product? {
        val feedJson =
            try {
                val response = kotlinx.coroutines.runBlocking { httpTransport.get(productFeedUrl) }
                response.body
            } catch (e: Exception) {
                terminal.error("Failed to fetch product feed: ${e.message}")
                return null
            }

        val products: List<Product> = mapper.readValue(feedJson)
        return products.find { it.code == code }
    }

    fun selectLatestCompatibleRelease(
        product: Product,
        reqType: String,
    ): ReleaseInfo? {
        // Prefer matching the current release version
        val preferred =
            product.releases
                .filter { it.majorVersion == Linters.RELEASE_VERSION && it.type == reqType }
                .maxByOrNull { it.date }
        if (preferred != null) return preferred

        // Fall back to latest release of the requested type
        return product.releases
            .filter { it.type == reqType }
            .maxByOrNull { it.date }
    }

    fun getPluginsUrl(ideUrl: String): String {
        val url = ideUrl.replace("-aarch64", "")
        return when {
            ".sit" in url -> url.replace(".sit", "-custom-plugins.zip")
            ".win.zip" in url -> url.replace(".win.zip", "-custom-plugins.zip")
            else -> url.replace(".tar.gz", "-custom-plugins.zip")
        }
    }

    suspend fun verifySha256(
        checksumUrl: String,
        filePath: Path,
        workDir: Path,
    ) {
        val checksumFile = workDir.resolve("${filePath.fileName}.sha256")
        httpTransport.download(checksumUrl, checksumFile)

        try {
            val expectedChecksum = fileSystem.read(checksumFile).trim().split(" ")[0]
            val actualChecksum = computeSha256(filePath)

            if (actualChecksum != expectedChecksum) {
                fileSystem.delete(filePath)
                error("Checksums don't match. Expected: $expectedChecksum, Actual: $actualChecksum")
            }
            terminal.info("Checksum of downloaded IDE was verified")
        } finally {
            try {
                fileSystem.delete(checksumFile)
            } catch (_: Exception) {
            }
        }
    }

    private fun computeSha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        java.io.FileInputStream(path.toFile()).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractArchive(
        archivePath: Path,
        targetDir: Path,
        extension: String,
    ) {
        when (extension) {
            "sit", "zip" -> fileSystem.extractArchive(archivePath, targetDir)
            "gz" -> fileSystem.extractArchive(archivePath, targetDir)
            else -> error("Unsupported file extension: $extension")
        }
    }

    private fun resolveInstallDir(installDir: Path): Path {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "mac" in os || "darwin" in os -> {
                val appDirs = fileSystem.walk(installDir, "*.app").toList()
                if (appDirs.size == 1) appDirs[0].resolve("Contents") else installDir
            }
            "win" in os -> {
                val subDirs = fileSystem.walk(installDir).filter { it.parent == installDir }.toList()
                if (subDirs.size == 1) subDirs[0] else installDir
            }
            else -> installDir
        }
    }

    private fun resolveDownloadType(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch == "aarch64" || arch == "arm64"

        return when {
            "mac" in os || "darwin" in os -> if (isArm) "macSitM1" else "macSit"
            "win" in os -> if (isArm) "windowsZipARM64" else "windowsZip"
            else -> if (isArm) "linuxARM64" else "linux"
        }
    }

    companion object {
        const val DEFAULT_PRODUCT_FEED_URL =
            "https://raw.githubusercontent.com/JetBrains/qodana-docker/main/feed/releases.json"
    }
}

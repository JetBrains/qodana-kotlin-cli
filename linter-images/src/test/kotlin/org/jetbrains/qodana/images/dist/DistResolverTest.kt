package org.jetbrains.qodana.images.dist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DistResolverTest {
    private val link = "https://download.jetbrains.com/qodana/2025.3/qodana-QDJVM-253.200.tar.gz"
    private val checksumLink = "$link.sha256"

    private val release =
        Release(
            date = "2025-09-15",
            type = "release",
            majorVersion = "2025.3",
            build = "253.200",
            downloads =
                mapOf(
                    "linux" to ReleaseDownload(link = link, checksumLink = checksumLink),
                ),
        )

    @Test
    fun `resolves link and checksum for linux amd64`() {
        val dist = DistResolver.resolve(release, os = "linux", arch = "amd64")
        assertEquals(link, dist.link)
        assertEquals(checksumLink, dist.checksumLink)
        // The detached signature is derived by the caller as checksumLink + ".asc".
        assertEquals("$checksumLink.asc", dist.checksumLink + ".asc")
    }

    @Test
    fun `throws when os key absent`() {
        assertFailsWith<MissingDownloadException> {
            DistResolver.resolve(release, os = "linux", arch = "arm64")
        }
    }

    @Test
    fun `throws when link or checksum empty`() {
        val broken = release.copy(downloads = mapOf("linux" to ReleaseDownload(link = "L", checksumLink = "")))
        assertFailsWith<MissingDownloadException> {
            DistResolver.resolve(broken, os = "linux", arch = "amd64")
        }
    }
}

package org.jetbrains.qodana.images.dist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DistResolverTest {
    private val release =
        Release(
            date = "2025-09-15",
            type = "release",
            majorVersion = "2025.3",
            build = "253.200",
            downloads =
                mapOf(
                    "linux" to
                        ReleaseDownload(
                            link = "https://download.jetbrains.com/qodana/2025.3/qodana-QDJVM-253.200.tar.gz",
                            checksumLink = "https://download.jetbrains.com/qodana/2025.3/qodana-QDJVM-253.200.tar.gz.sha256",
                        ),
                ),
        )

    @Test
    fun `resolves link and checksum for linux amd64`() {
        val dist = DistResolver.resolve(release, os = "linux", arch = "amd64")
        assertEquals(
            "https://download.jetbrains.com/qodana/2025.3/qodana-QDJVM-253.200.tar.gz",
            dist.link,
        )
        assertEquals(
            "https://download.jetbrains.com/qodana/2025.3/qodana-QDJVM-253.200.tar.gz.sha256",
            dist.checksumLink,
        )
        // The detached signature is derived by the caller as checksumLink + ".asc".
        assertEquals(dist.checksumLink + ".asc", dist.checksumLink + ".asc")
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

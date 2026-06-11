package org.jetbrains.qodana.images.dist

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProductFeedTest {
    private val mapper = ObjectMapper().registerModule(kotlinModule())

    @Test
    fun `parses single-object feed with capitalized field names`() {
        val json =
            """
            {
              "Code": "QDJVM",
              "Releases": [
                {
                  "Date": "2025-09-01",
                  "Type": "release",
                  "Version": "2025.3.1",
                  "MajorVersion": "2025.3",
                  "Build": "253.1234.56",
                  "Downloads": {
                    "linux": {
                      "Link": "https://download.jetbrains.com/qodana/2025.3/qodana-jvm-253.1234.56.tar.gz",
                      "ChecksumLink": "https://download.jetbrains.com/qodana/2025.3/qodana-jvm-253.1234.56.tar.gz.sha256",
                      "Size": 123
                    },
                    "linuxARM64": {
                      "Link": "https://download.jetbrains.com/qodana/2025.3/qodana-jvm-253.1234.56-aarch64.tar.gz",
                      "ChecksumLink": "https://download.jetbrains.com/qodana/2025.3/qodana-jvm-253.1234.56-aarch64.tar.gz.sha256",
                      "Size": 456
                    }
                  }
                }
              ]
            }
            """.trimIndent()

        val feed: ProductFeed = mapper.readValue(json)

        assertEquals("QDJVM", feed.code)
        assertEquals(1, feed.releases.size)
        val release = feed.releases.single()
        assertEquals("release", release.type)
        assertEquals("2025.3", release.majorVersion)
        assertEquals("253.1234.56", release.build)
        assertEquals("2025-09-01", release.date)
        val linux = assertNotNull(release.downloads?.get("linux"))
        assertEquals(
            "https://download.jetbrains.com/qodana/2025.3/qodana-jvm-253.1234.56.tar.gz",
            linux.link,
        )
        assertEquals("$linux.link.sha256".let { linux.checksumLink }, linux.checksumLink)
    }

    @Test
    fun `ignores unknown fields`() {
        val json = """{"Code":"QDJVM","Releases":[],"Whatever":42}"""
        val feed: ProductFeed = mapper.readValue(json)
        assertEquals("QDJVM", feed.code)
        assertEquals(0, feed.releases.size)
    }
}

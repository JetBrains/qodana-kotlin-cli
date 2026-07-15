package org.jetbrains.qodana.images.registry

import org.jetbrains.qodana.images.RegistryTag
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpaceRegistryClientTest {
    private val fake = FakeOciServer()
    private val client = SpaceRegistryClient(fake.username, fake.password, scheme = "http")
    private val image = "p/sa/qodana-kcli-images/qodana-jvm"
    private val repo get() = "${fake.host}/$image"
    private val api = "/v2/$image"

    @AfterTest
    fun stopServer() = fake.stop()

    /** Registers the tags/list endpoint returning [names] (no pagination). */
    private fun wireTags(vararg names: String) {
        val body = names.joinToString(",", "{\"tags\":[", "]}") { "\"$it\"" }
        fake.on("GET", "$api/tags/list") { ex -> FakeOciServer.respond(ex, 200, body, JSON) }
    }

    /** Wires the linux/amd64 child manifest + config blob shared by the index fixtures below. */
    private fun wireSharedChildChain(created: String) {
        fake.on("GET", "$api/manifests/sha256:aaaa") { ex ->
            FakeOciServer.respond(ex, 200, """{"config":{"digest":"sha256:cccc"}}""", MANIFEST)
        }
        fake.on("GET", "$api/blobs/sha256:cccc") { ex ->
            FakeOciServer.respond(ex, 200, """{"created":"$created"}""", CONFIG)
        }
    }

    /** Wires [tag]'s index manifest, pointing at the shared child. */
    private fun wireIndex(tag: String) {
        fake.on("GET", "$api/manifests/$tag") { ex ->
            FakeOciServer.respond(ex, 200, LINUX_AMD64_INDEX, INDEX)
        }
    }

    @Test
    fun `listTags resolves pushed from the linux-amd64 config blob's created field`() {
        wireSharedChildChain("2026-01-02T03:04:05.123Z")
        wireIndex("t1")
        wireTags("t1")

        val tags = client.listTags(repo)
        assertEquals(listOf(RegistryTag("t1", Instant.parse("2026-01-02T03:04:05.123Z"))), tags)
    }

    @Test
    fun `listTags skips non-linux-amd64 entries like buildkit attestation manifests`() {
        val mixed =
            """{"manifests":[
              {"digest":"sha256:bad","platform":{"architecture":"unknown","os":"unknown"}},
              {"digest":"sha256:aaaa","platform":{"architecture":"amd64","os":"linux"}},
              {"digest":"sha256:bad2","platform":{"architecture":"arm64","os":"linux"}}
            ]}"""
        fake.on("GET", "$api/manifests/t1") { ex -> FakeOciServer.respond(ex, 200, mixed, INDEX) }
        wireSharedChildChain("2026-03-03T00:00:00Z")
        wireTags("t1")

        val tags = client.listTags(repo)
        assertEquals(Instant.parse("2026-03-03T00:00:00Z"), tags.single().pushed)
    }

    @Test
    fun `listTags falls back to a safe sentinel and warns when no linux-amd64 manifest exists`() {
        val noAmd64 = """{"manifests":[{"digest":"sha256:bad","platform":{"architecture":"unknown","os":"unknown"}}]}"""
        fake.on("GET", "$api/manifests/t1") { ex -> FakeOciServer.respond(ex, 200, noAmd64, INDEX) }
        wireTags("t1")

        val tags = client.listTags(repo)
        assertEquals(Instant.MAX, tags.single().pushed, "an unresolvable tag must not look prunable-by-age")
    }

    @Test
    fun `listTags follows Link-header pagination across 3 pages, with no page cap`() {
        wireSharedChildChain("2026-05-05T00:00:00Z")
        listOf("a", "b", "c").forEach { wireIndex(it) }
        fake.on("GET", "$api/tags/list") { ex ->
            when (ex.requestURI.query) {
                "last=b" -> FakeOciServer.respond(ex, 200, """{"tags":["c"]}""", JSON)
                "last=a" -> {
                    ex.responseHeaders.add("Link", """<$api/tags/list?last=b>; rel="next"""")
                    FakeOciServer.respond(ex, 200, """{"tags":["b"]}""", JSON)
                }
                else -> {
                    ex.responseHeaders.add("Link", """<$api/tags/list?last=a>; rel="next"""")
                    FakeOciServer.respond(ex, 200, """{"tags":["a"]}""", JSON)
                }
            }
        }

        val tags = client.listTags(repo)
        assertEquals(listOf("a", "b", "c"), tags.map { it.name })
    }

    @Test
    fun `listTags fails loud on a Link header that signals a next page in an unrecognized format`() {
        fake.on("GET", "$api/tags/list") { ex ->
            ex.responseHeaders.add("Link", """weird-format rel="next"""")
            FakeOciServer.respond(ex, 200, """{"tags":["t1"]}""", JSON)
        }

        assertFailsWith<IllegalStateException> { client.listTags(repo) }
    }

    @Test
    fun `listTags fails loud if pagination cycles back to an already-fetched page`() {
        fake.on("GET", "$api/tags/list") { ex ->
            ex.responseHeaders.add("Link", """<$api/tags/list>; rel="next"""")
            FakeOciServer.respond(ex, 200, """{"tags":["a"]}""", JSON)
        }

        assertFailsWith<IllegalStateException> { client.listTags(repo) }
    }

    @Test
    fun `listTags detects a cosmetically-reformatted repeat page (params reordered) as a cycle`() {
        fake.on("GET", "$api/tags/list") { ex ->
            // First page links to ?n=1&last=a; that page links back with params swapped — the same logical
            // page. A syntactic URI compare would miss it and loop forever; the canonical key must catch it.
            val link =
                if (ex.requestURI.query == null) {
                    """<$api/tags/list?n=1&last=a>; rel="next""""
                } else {
                    """<$api/tags/list?last=a&n=1>; rel="next""""
                }
            ex.responseHeaders.add("Link", link)
            FakeOciServer.respond(ex, 200, """{"tags":["a"]}""", JSON)
        }

        assertFailsWith<IllegalStateException> { client.listTags(repo) }
    }

    @Test
    fun `listTags fails loud when the tags-list endpoint returns a non-200`() {
        fake.on("GET", "$api/tags/list") { ex ->
            ex.sendResponseHeaders(500, -1)
            ex.close()
        }

        assertFailsWith<IllegalStateException> { client.listTags(repo) }
    }

    @Test
    fun `resolveDigest returns the Docker-Content-Digest header for the tag's index manifest`() {
        fake.on("GET", "$api/manifests/t1") { ex ->
            ex.responseHeaders.add("Docker-Content-Digest", "sha256:deadbeef")
            FakeOciServer.respond(ex, 200, """{"manifests":[]}""", INDEX)
        }

        assertEquals("sha256:deadbeef", client.resolveDigest(repo, "t1"))
    }

    @Test
    fun `resolveDigest returns null when the tag has no index manifest`() {
        fake.on("GET", "$api/manifests/t1") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }

        assertNull(client.resolveDigest(repo, "t1"))
    }

    @Test
    fun `resolveDigest fails loud on a non-200-non-404 response`() {
        fake.on("GET", "$api/manifests/t1") { ex ->
            ex.sendResponseHeaders(500, -1)
            ex.close()
        }

        assertFailsWith<IllegalStateException> { client.resolveDigest(repo, "t1") }
    }

    @Test
    fun `resolveDigest requests a pull-only scoped token`() {
        fake.on("GET", "$api/manifests/t1") { ex ->
            ex.responseHeaders.add("Docker-Content-Digest", "sha256:deadbeef")
            FakeOciServer.respond(ex, 200, """{"manifests":[]}""", INDEX)
        }

        client.resolveDigest(repo, "t1")

        assertTrue(fake.tokenRequests.any { it.endsWith(":pull") }, fake.tokenRequests.toString())
    }

    @Test
    fun `deleteByDigest issues a DELETE and succeeds on 202`() {
        var deleted = false
        fake.on("DELETE", "$api/manifests/sha256:deadbeef") { ex ->
            deleted = true
            ex.sendResponseHeaders(202, -1)
            ex.close()
        }

        client.deleteByDigest(repo, "sha256:deadbeef")
        assertTrue(deleted)
    }

    @Test
    fun `deleteByDigest treats a 404 as success since a digest-delete is idempotent ensure-absent`() {
        fake.on("DELETE", "$api/manifests/sha256:gone") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }

        client.deleteByDigest(repo, "sha256:gone") // already-absent is the desired end state; must not throw
    }

    @Test
    fun `deleteByDigest fails loud on a non-202-non-404 response`() {
        fake.on("DELETE", "$api/manifests/sha256:deadbeef") { ex ->
            ex.sendResponseHeaders(400, -1)
            ex.close()
        }

        assertFailsWith<IllegalStateException> { client.deleteByDigest(repo, "sha256:deadbeef") }
    }

    @Test
    fun `deleteByDigest requests a pull,delete scoped token, not merely pull`() {
        fake.on("DELETE", "$api/manifests/sha256:deadbeef") { ex ->
            ex.sendResponseHeaders(202, -1)
            ex.close()
        }

        client.deleteByDigest(repo, "sha256:deadbeef")

        assertTrue(fake.tokenRequests.any { it.endsWith(":pull,delete") }, fake.tokenRequests.toString())
    }

    @Test
    fun `pushedAt follows a redirect when fetching the config blob`() {
        wireIndex("t1")
        wireTags("t1")
        fake.on("GET", "$api/manifests/sha256:aaaa") { ex ->
            FakeOciServer.respond(ex, 200, """{"config":{"digest":"sha256:cccc"}}""", MANIFEST)
        }
        fake.on("GET", "$api/blobs/sha256:cccc") { ex ->
            ex.responseHeaders.add("Location", "http://${fake.host}/signed-blob")
            ex.sendResponseHeaders(307, -1)
            ex.close()
        }
        fake.on("GET", "/signed-blob") { ex ->
            FakeOciServer.respond(ex, 200, """{"created":"2026-04-04T00:00:00Z"}""", CONFIG)
        }

        val tags = client.listTags(repo)
        assertEquals(Instant.parse("2026-04-04T00:00:00Z"), tags.single().pushed)
    }

    @Test
    fun `repeated calls reuse a cached token instead of re-authenticating every time`() {
        wireSharedChildChain("2026-06-06T00:00:00Z")
        wireIndex("t1")
        wireIndex("t2")
        wireTags("t1", "t2")

        client.listTags(repo)

        assertEquals(1, fake.tokenRequests.size, "one action (pull) -> one token request, not one per HTTP call")
    }

    @Test
    fun `resolveDigest reuses the digest pushedAt already learned from the same manifest, no extra fetch`() {
        var indexFetches = 0
        fake.on("GET", "$api/manifests/t1") { ex ->
            indexFetches++
            ex.responseHeaders.add("Docker-Content-Digest", "sha256:aaaa")
            FakeOciServer.respond(ex, 200, LINUX_AMD64_INDEX, INDEX)
        }
        wireSharedChildChain("2026-07-07T00:00:00Z")
        wireTags("t1")

        client.listTags(repo)
        val digest = client.resolveDigest(repo, "t1")

        assertEquals("sha256:aaaa", digest)
        assertEquals(1, indexFetches, "resolveDigest must reuse the digest listTags already read, not re-fetch")
    }

    @Test
    fun `evicts a cached token and retries once after a 401, then succeeds`() {
        fake.on("GET", "$api/manifests/t1") { ex ->
            ex.responseHeaders.add("Docker-Content-Digest", "sha256:deadbeef")
            FakeOciServer.respond(ex, 200, """{"manifests":[]}""", INDEX)
        }
        fake.rejectNextAuthCount = 1

        val digest = client.resolveDigest(repo, "t1")

        assertEquals("sha256:deadbeef", digest)
        assertEquals(2, fake.tokenRequests.size, "must re-authenticate exactly once after the 401")
    }

    @Test
    fun `fails loud if a retried request still gets 401 after re-authenticating`() {
        fake.on("GET", "$api/manifests/t1") { ex ->
            ex.responseHeaders.add("Docker-Content-Digest", "sha256:deadbeef")
            FakeOciServer.respond(ex, 200, """{"manifests":[]}""", INDEX)
        }
        fake.rejectNextAuthCount = 2

        assertFailsWith<IllegalStateException> { client.resolveDigest(repo, "t1") }
    }

    @Test
    fun `a fresh listTags drops a stale cached digest so a re-verify observes true current state`() {
        // The digest cache exists only to skip a redundant re-fetch within one listing; it must NOT let a
        // second (re-verify) listing return a digest learned in the first. Here the tag's index 200s on the
        // first listing (cached) but 404s on the second — resolveDigest after the second listing must
        // re-fetch and see the 404 (null), not return the stale first-listing digest.
        var manifestPresent = true
        fake.on("GET", "$api/manifests/t1") { ex ->
            if (!manifestPresent) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
            } else {
                ex.responseHeaders.add("Docker-Content-Digest", "sha256:aaaa")
                FakeOciServer.respond(ex, 200, LINUX_AMD64_INDEX, INDEX)
            }
        }
        wireSharedChildChain("2026-08-08T00:00:00Z")
        wireTags("t1")

        client.listTags(repo)
        assertEquals("sha256:aaaa", client.resolveDigest(repo, "t1"))
        manifestPresent = false
        client.listTags(repo) // a fresh listing must invalidate the per-listing digest cache
        assertNull(client.resolveDigest(repo, "t1"), "must re-fetch after a fresh listTags, not serve the stale digest")
    }

    @Test
    fun `a token request failure surfaces a clear error`() {
        wireTags()
        val badClient = SpaceRegistryClient(fake.username, "wrong-password", scheme = "http")

        val thrown = assertFailsWith<IllegalStateException> { badClient.listTags(repo) }
        assertTrue(thrown.message!!.contains("token request failed"), thrown.message.toString())
    }

    private companion object {
        const val INDEX = "application/vnd.oci.image.index.v1+json"
        const val MANIFEST = "application/vnd.oci.image.manifest.v1+json"
        const val CONFIG = "application/vnd.oci.image.config.v1+json"
        const val JSON = "application/json"
        const val LINUX_AMD64_INDEX =
            """{"manifests":[{"digest":"sha256:aaaa","platform":{"architecture":"amd64","os":"linux"}}]}"""
    }
}

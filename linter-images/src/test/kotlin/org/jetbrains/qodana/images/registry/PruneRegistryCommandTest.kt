package org.jetbrains.qodana.images.registry

import com.github.ajalt.clikt.core.parse
import org.jetbrains.qodana.images.RegistryTag
import org.jetbrains.qodana.images.ResolveImageMetaCommand
import org.jetbrains.qodana.images.ResolvePublishMatrixCommand
import org.jetbrains.qodana.images.ResolveTagsCommand
import org.jetbrains.qodana.images.RuntimeResolver
import org.jetbrains.qodana.images.computeTagPrune
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PruneRegistryCommandTest {
    private val imagesDir = Path.of("docker/images")
    private val clangVersions = Path.of("docker/clang-versions.txt")
    private val rubyVersions = Path.of("docker/ruby-versions.txt")
    private val runtime = RuntimeResolver(imagesDir, clangVersions, rubyVersions)
    private val meta = ResolveImageMetaCommand(imagesDir = imagesDir, runtime = runtime)
    private val gradleProperties =
        Files.createTempFile("gradle", ".properties").also { Files.writeString(it, "version=2026.2\n") }
    private val publishMatrix =
        ResolvePublishMatrixCommand(
            imagesDir = imagesDir,
            clangVersions = clangVersions,
            rubyVersions = rubyVersions,
            meta = meta,
            tags = ResolveTagsCommand(gradleProperties = gradleProperties, runtime = runtime),
        )

    private fun command(
        client: RegistryClient,
        now: Instant = Instant.parse("2026-06-15T00:00:00Z"),
    ) = PruneRegistryCommand(client = { client }, publishMatrix = publishMatrix, now = { now })

    @Test
    fun `queries exactly one repo per distinct image, independently derived from the docker-images env files`() {
        val client = FakeRegistryClient()
        command(client).parse(listOf("--registry", "reg.example/proj"))

        val expectedImages =
            Files
                .newDirectoryStream(imagesDir, "*.env")
                .use { stream ->
                    stream.map { it.fileName.toString().removeSuffix(".env") }
                }.sorted()
        assertEquals(expectedImages.map { "reg.example/proj/$it" }, client.listedRepos.sorted())
    }

    @Test
    fun `deletes exactly the tags computeTagPrune selects, deduped by shared digest`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val dates = (1..9).map { "2026.2-nightly.202606%02d".format(it) }
        val moving = "2026.2-nightly"
        val repo = "reg.example/proj/qodana-jvm"
        val tags = dates.map { RegistryTag(it, now) } + RegistryTag(moving, now)
        val expectedPruned = computeTagPrune(tags, now)
        val digests =
            dates.filterNot { it in expectedPruned }.associate { (repo to it) to "sha256:kept-$it" } +
                expectedPruned.associate { (repo to it) to "sha256:shared" } +
                mapOf((repo to moving) to "sha256:moving")
        val client = FakeRegistryClient(tagsByRepo = mapOf(repo to tags), digestsByRepoAndTag = digests)

        command(client, now).parse(listOf("--registry", "reg.example/proj"))

        assertEquals(listOf(repo to "sha256:shared"), client.deletedDigests)
    }

    @Test
    fun `skips a digest shared with a kept tag instead of collaterally deleting it`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val pruned = "2026.2-snapshot.aaa1111"
        val kept = "2026.2-nightly"
        val tags = listOf(RegistryTag(pruned, now.minus(Duration.ofDays(10))), RegistryTag(kept, now))
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to tags),
                digestsByRepoAndTag = mapOf((repo to pruned) to "sha256:shared", (repo to kept) to "sha256:shared"),
            )

        command(client, now).parse(listOf("--registry", "reg.example/proj"))

        assertTrue(client.deletedDigests.isEmpty(), "a digest a kept tag still references must never be deleted")
    }

    @Test
    fun `excludes a tag with no resolvable index from the digest-safety map, without blocking a legitimate prune`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val pruned = "2026.2-snapshot.aaa1111"
        val foreign = "latest"
        val tags = listOf(RegistryTag(pruned, now.minus(Duration.ofDays(10))), RegistryTag(foreign, now))
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to tags),
                digestsByRepoAndTag = mapOf((repo to pruned) to "sha256:a", (repo to foreign) to null),
            )

        command(client, now).parse(listOf("--registry", "reg.example/proj"))

        assertEquals(listOf(repo to "sha256:a"), client.deletedDigests)
    }

    @Test
    fun `re-verifies each safe digest immediately before deleting, catching a concurrent re-point`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val snapshot = "2026.2-snapshot.aaa1111"
        val firstListing = listOf(RegistryTag(snapshot, now.minus(Duration.ofDays(10))))
        val secondListing = firstListing + RegistryTag("2026.2-nightly", now)
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to firstListing),
                tagsByRepoOnSecondCall = mapOf(repo to secondListing),
                digestsByRepoAndTag =
                    mapOf((repo to snapshot) to "sha256:x", (repo to "2026.2-nightly") to "sha256:x"),
            )

        command(client, now).parse(listOf("--registry", "reg.example/proj"))

        assertTrue(client.deletedDigests.isEmpty(), "a re-point between plan and delete must block the delete")
    }

    @Test
    fun `re-verifies all safe groups with exactly one fresh listing, not once per group`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val snapshots = listOf("2026.2-snapshot.aaa1111", "2026.2-snapshot.bbb2222", "2026.2-snapshot.ccc3333")
        val tags = snapshots.map { RegistryTag(it, now.minus(Duration.ofDays(10))) }
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to tags),
                digestsByRepoAndTag = snapshots.associate { (repo to it) to "sha256:$it" },
            )

        command(client, now).parse(listOf("--registry", "reg.example/proj"))

        assertEquals(3, client.deletedDigests.size)
        assertEquals(2, client.listedRepos.count { it == repo }, "one plan listing + one bounded re-verify per repo")
    }

    @Test
    fun `dry-run reports the plan but deletes nothing`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val dates = (1..9).map { "2026.2-nightly.202606%02d".format(it) }
        val repo = "reg.example/proj/qodana-jvm"
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to dates.map { RegistryTag(it, now) }),
                digestsByRepoAndTag = dates.associate { (repo to it) to "sha256:$it" },
            )

        command(client, now).parse(listOf("--registry", "reg.example/proj", "--dry-run"))

        assertTrue(client.deletedDigests.isEmpty(), "dry-run must not delete")
        assertTrue(client.resolvedDigests.isNotEmpty(), "dry-run still resolves digests to report an accurate plan")
    }

    @Test
    fun `snapshot-max-age-days flag overrides the default so a fresh snapshot can be forced for review`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to listOf(RegistryTag("2026.2-snapshot.aaa1111", now.minusSeconds(3600)))),
                digestsByRepoAndTag = mapOf((repo to "2026.2-snapshot.aaa1111") to "sha256:a"),
            )

        command(client, now).parse(listOf("--registry", "reg.example/proj", "--snapshot-max-age-days", "0"))

        assertEquals(listOf(repo to "sha256:a"), client.deletedDigests)
    }

    @Test
    fun `without the override flag, a fresh snapshot survives under the real 7-day default`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to listOf(RegistryTag("2026.2-snapshot.aaa1111", now.minusSeconds(3600)))),
                digestsByRepoAndTag = mapOf((repo to "2026.2-snapshot.aaa1111") to "sha256:a"),
            )

        command(client, now).parse(listOf("--registry", "reg.example/proj"))

        assertTrue(client.deletedDigests.isEmpty(), "a 1-hour-old snapshot must survive the real default (7 days)")
    }

    @Test
    fun `isolates a failing repo, still prunes the others, and reports an aggregate failure`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val goodRepo = "reg.example/proj/qodana-go"
        val badRepo = "reg.example/proj/qodana-jvm"
        val pruned = "2026.2-snapshot.aaa1111"
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(goodRepo to listOf(RegistryTag(pruned, now.minus(Duration.ofDays(10))))),
                digestsByRepoAndTag = mapOf((goodRepo to pruned) to "sha256:g"),
                failingRepos = setOf(badRepo),
            )

        val thrown = assertFailsWith<Exception> { command(client, now).parse(listOf("--registry", "reg.example/proj")) }

        assertTrue(thrown.message!!.contains("qodana-jvm"), thrown.message.toString())
        assertEquals(listOf(goodRepo to "sha256:g"), client.deletedDigests)
    }

    @Test
    fun `isolates a failing delete within a repo, still attempts the remaining groups, and reports the failure`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val a = "2026.2-snapshot.aaa1111"
        val b = "2026.2-snapshot.bbb2222"
        val old = now.minus(Duration.ofDays(10))
        val tags = listOf(RegistryTag(a, old), RegistryTag(b, old))
        val delegate =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to tags),
                digestsByRepoAndTag = mapOf((repo to a) to "sha256:a", (repo to b) to "sha256:b"),
            )
        val client =
            object : RegistryClient by delegate {
                override fun deleteByDigest(
                    repo: String,
                    digest: String,
                ) {
                    if (digest == "sha256:a") error("simulated delete failure")
                    delegate.deleteByDigest(repo, digest)
                }
            }

        val thrown = assertFailsWith<Exception> { command(client, now).parse(listOf("--registry", "reg.example/proj")) }

        assertTrue(thrown.message!!.contains("qodana-jvm"), thrown.message.toString())
        assertEquals(listOf(repo to "sha256:b"), delegate.deletedDigests)
    }

    @Test
    fun `preserves the underlying exception as the cause of the aggregate failure`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val badRepo = "reg.example/proj/qodana-jvm"
        val client = FakeRegistryClient(failingRepos = setOf(badRepo))

        val thrown = assertFailsWith<Exception> { command(client, now).parse(listOf("--registry", "reg.example/proj")) }

        assertTrue(thrown.cause is IllegalStateException, "the underlying exception must be preserved as the cause")
        assertTrue(thrown.cause!!.message!!.contains("simulated failure"), thrown.cause!!.message.toString())
    }

    @Test
    fun `preserves the underlying DELETE exception through the aggregate cause chain`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val pruned = "2026.2-snapshot.aaa1111"
        val delegate =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to listOf(RegistryTag(pruned, now.minus(Duration.ofDays(10))))),
                digestsByRepoAndTag = mapOf((repo to pruned) to "sha256:a"),
            )
        val client =
            object : RegistryClient by delegate {
                override fun deleteByDigest(
                    repo: String,
                    digest: String,
                ): Unit = error("boom-delete-detail")
            }

        val thrown = assertFailsWith<Exception> { command(client, now).parse(listOf("--registry", "reg.example/proj")) }

        // The real delete exception must survive somewhere in the cause chain, not be replaced by a bare synthetic.
        val chain = generateSequence(thrown as Throwable?) { it.cause }.toList()
        val messages = chain.map { it.message }
        assertTrue(messages.any { it?.contains("boom-delete-detail") == true }, messages.toString())
    }

    @Test
    fun `keep-nightly flag overrides the default, changing which generations are pruned`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val dates = (1..9).map { "2026.2-nightly.202606%02d".format(it) }
        val repo = "reg.example/proj/qodana-jvm"
        val tags = dates.map { RegistryTag(it, now) }
        // Each generation has a distinct digest, so pruned count == deleted-digest count.
        val expectedPruned = computeTagPrune(tags, now, keepNightly = 3)
        val digests = dates.associate { (repo to it) to "sha256:$it" }
        val client = FakeRegistryClient(tagsByRepo = mapOf(repo to tags), digestsByRepoAndTag = digests)

        command(client, now).parse(listOf("--registry", "reg.example/proj", "--keep-nightly", "3"))

        assertEquals(expectedPruned.map { "sha256:$it" }.toSet(), client.deletedDigests.map { it.second }.toSet())
        assertEquals(6, client.deletedDigests.size, "keeping 3 of 9 generations prunes the oldest 6")
    }

    @Test
    fun `skips deletion when a pruned tag is repushed to a new digest between plan and re-verify`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val snapshot = "2026.2-snapshot.aaa1111"
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to listOf(RegistryTag(snapshot, now.minus(Duration.ofDays(10))))),
                digestsByRepoAndTag = mapOf((repo to snapshot) to "sha256:plan"),
                digestsByRepoAndTagOnSecondCall = mapOf((repo to snapshot) to "sha256:repushed"),
            )

        command(client, now).parse(listOf("--registry", "reg.example/proj"))

        assertTrue(client.deletedDigests.isEmpty(), "repushed digest unreferenced; the stale delete must be skipped")
    }

    @Test
    fun `fails loud when a prune candidate's own digest cannot be resolved, naming the tag`() {
        val now = Instant.parse("2026-06-15T00:00:00Z")
        val repo = "reg.example/proj/qodana-jvm"
        val pruned = "2026.2-snapshot.aaa1111"
        val client =
            FakeRegistryClient(
                tagsByRepo = mapOf(repo to listOf(RegistryTag(pruned, now.minus(Duration.ofDays(10))))),
                digestsByRepoAndTag = mapOf((repo to pruned) to null), // our own prune target with no resolvable index
            )

        val thrown = assertFailsWith<Exception> { command(client, now).parse(listOf("--registry", "reg.example/proj")) }

        assertTrue(client.deletedDigests.isEmpty())
        // A provable-invariant violation must fail the run (non-zero exit) and name the offending tag,
        // not be a warning in a green job that nobody reads.
        val chain = generateSequence(thrown as Throwable?) { it.cause }.toList()
        assertTrue(chain.any { it.message?.contains(pruned) == true }, chain.map { it.message }.toString())
    }
}

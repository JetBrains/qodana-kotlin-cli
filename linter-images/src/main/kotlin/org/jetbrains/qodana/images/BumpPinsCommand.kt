package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import org.jetbrains.qodana.images.dist.FeedClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Per-linter within-major drift bump. For each `.env` under the images dir that pins an IDE dist
 * (has `QD_LINTER_SLUG`), fetch the feed and pick the NEWEST release (max by `Date`) whose
 * `MajorVersion == QD_VERSION` AND `Type == QD_RELEASE_TYPE`. If its `Build` differs from the pinned
 * `QD_BUILD`, rewrite ONLY the `QD_BUILD` line — `QD_VERSION` stays the major (EnvContractTest pins it
 * byte-identical to phase-0-decisions.md, and the major does not move within a within-major bump).
 *
 * The selected build is resolved ONCE per distinct `(slug, channel, major, releaseType)` and reused
 * across the `.env` files that share that exact pin (jvm + android), so the two never diverge from a
 * mid-run feed change. After the `.env` rewrites, the matching `QODANA_<SLUG>_BUILD` row in
 * [decisionsFile] (asserted present) is synced so the produced drift PR keeps EnvContractTest green.
 * Cross-major builds are never selected (this is the ONLY place a newer build is chosen by date;
 * `ReleaseSelector` stays an exact pin).
 */
class BumpPinsCommand(
    private val feedClient: FeedClient,
    private val getEnv: (String) -> String? = System::getenv,
) : CliktCommand(name = "bump-pins") {
    private val feedUrlOption by option("--feed-url").default(DEFAULT_FEED_URL)
    private val imagesDir by option("--images-dir").path(mustExist = true).required()
    private val decisionsFile by option("--decisions-file").path()

    override fun run() {
        val decisions = decisionsFile ?: imagesDir.parent?.resolve("docs/phase-0-decisions.md")
        rewrite(imagesDir, decisions, feedUrlOption)
    }

    /**
     * Rewrites within-major build pins for every `.env` file under [dir] that pins an IDE dist.
     * Pure of any Clikt option delegate so it is unit-testable without parsing a command line.
     */
    fun rewrite(
        dir: Path,
        decisions: Path? = dir.parent?.resolve("docs/phase-0-decisions.md"),
        feedUrl: String = DEFAULT_FEED_URL,
    ) {
        val envs =
            Files.list(dir).use { stream ->
                stream.filter { it.name.endsWith(".env") }.sorted().toList()
            }
        // Resolve once per exact dist pin so files sharing it (jvm + android) agree on the new build.
        val newestBuildByPin = mutableMapOf<String, String>()
        envs.forEach { bumpEnv(it, decisions, feedUrl, newestBuildByPin) }
    }

    private fun bumpEnv(
        env: Path,
        decisions: Path?,
        feedUrl: String,
        newestBuildByPin: MutableMap<String, String>,
    ) {
        val kv = parse(env)
        // clang etc. have no IDE dist — a missing slug or major means nothing to bump (skip the file).
        val slug = kv["QD_LINTER_SLUG"]
        val major = kv["QD_VERSION"]
        if (slug == null || major == null) return
        val releaseType = kv["QD_RELEASE_TYPE"] ?: "release"
        val channel = kv["QD_CHANNEL"] ?: "public"

        // Key on the FULL selection input — two files sharing only (slug, channel) but differing in
        // major or release type must NOT reuse each other's resolved build.
        val newBuild =
            newestBuildByPin.getOrPut("$slug|$channel|$major|$releaseType") {
                resolveNewestBuild(feedUrl, slug, major, releaseType, channel) ?: ""
            }
        // Rewrite only when the feed offers a different within-major build; otherwise leave the file as-is.
        if (newBuild.isNotEmpty() && newBuild != kv["QD_BUILD"]) {
            rewriteLine(env, "QD_BUILD", newBuild)
            decisions?.takeIf { it.exists() }?.let { syncDecisions(it, slug, newBuild) }
        }
    }

    private fun resolveNewestBuild(
        feedUrl: String,
        slug: String,
        major: String,
        releaseType: String,
        channel: String,
    ): String? {
        val token =
            if (channel == "private") {
                getEnv("QD_FEED_TOKEN")?.takeIf { it.isNotBlank() }
                    ?: error("--channel private for $slug but \$QD_FEED_TOKEN is unset or blank")
            } else {
                null
            }
        val feed = feedClient.fetch(feedUrl, slug, token)
        return feed.releases
            .filter { it.majorVersion == major && it.type == releaseType }
            .maxByOrNull { parseDate(it.date) }
            ?.build
    }

    private fun parse(env: Path): Map<String, String> =
        Files
            .readAllLines(env)
            .mapNotNull { line ->
                val t = line.substringBefore('#').trim()
                if ('=' in t) t.substringBefore('=').trim() to t.substringAfter('=').trim() else null
            }.toMap()

    /** Rewrites the single `KEY=...` line in place, preserving every other line, comments, and order. */
    private fun rewriteLine(
        env: Path,
        key: String,
        value: String,
    ) {
        val rewritten =
            Files.readAllLines(env).map { line ->
                if (line.trimStart().startsWith("$key=")) "$key=$value" else line
            }
        Files.write(env, rewritten)
    }

    /**
     * Syncs `QODANA_<SLUG>_BUILD = <value>` in the decisions doc (e.g. qodana-jvm → QODANA_JVM_BUILD).
     * Fails loudly if no such row exists — a silent no-op would produce a drift PR that breaks
     * EnvContractTest's byte-identity guard.
     */
    private fun syncDecisions(
        decisions: Path,
        slug: String,
        build: String,
    ) {
        val key = "QODANA_${slug.removePrefix("qodana-").uppercase()}_BUILD"
        val row = Regex("""^(\s*$key\s*=\s*)\S+""")
        var matched = 0
        val rewritten =
            Files.readAllLines(decisions).map { line ->
                row.find(line)?.let {
                    matched++
                    "${it.groupValues[1]}$build"
                } ?: line
            }
        check(matched == 1) { "expected exactly one '$key' row in $decisions, found $matched" }
        Files.write(decisions, rewritten)
    }

    /** ISO-8601 `YYYY-MM-DD` parse; fail loudly on an unexpected feed `Date` rather than mis-sorting. */
    private fun parseDate(date: String): LocalDate =
        runCatching { LocalDate.parse(date) }
            .getOrElse { error("feed Date is not ISO-8601 (YYYY-MM-DD): '$date'") }

    private companion object {
        const val DEFAULT_FEED_URL = "https://download.jetbrains.com/qodana/feed"
    }
}

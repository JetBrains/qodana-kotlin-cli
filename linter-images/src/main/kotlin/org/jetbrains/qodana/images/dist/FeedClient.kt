package org.jetbrains.qodana.images.dist

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.qodana.images.process.CommandRunner
import java.nio.file.Files

/**
 * `curl`s `<feedUrl>/<linter-slug>.releases.json` (a single feed object) through the [runner] and
 * parses it. A bearer `--header` is added IFF [token] is non-null. No separate Downloader port.
 */
class FeedClient(
    private val runner: CommandRunner,
) {
    private val mapper = ObjectMapper().registerModule(kotlinModule())

    fun fetch(
        feedUrl: String,
        linterSlug: String,
        token: String?,
    ): ProductFeed {
        val url = "${feedUrl.trimEnd('/')}/$linterSlug.releases.json"
        val target = Files.createTempFile("$linterSlug-releases", ".json")
        val cmd = mutableListOf("curl", "-fsSL", "-o", target.toString())
        if (token != null) cmd += listOf("--header", "Authorization: Bearer $token")
        cmd += url
        val result = runner.run(cmd)
        require(result.exitCode == 0) { "feed fetch failed for $url (exit ${result.exitCode}): ${result.stderr}" }
        return mapper.readValue(target.toFile())
    }
}

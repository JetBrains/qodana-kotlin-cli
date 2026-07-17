package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Files
import java.nio.file.Path

/**
 * Emits the full nightly publish matrix as a compact JSON array — one row per publishable (image, version):
 * every image at the family default (version ""), plus each versioned image's non-default versions. Each
 * row carries the per-image gating (token_gated / feed_required / compose_files) so the nightly workflow
 * can `matrix.include: fromJSON(...)` and call publish-image.yaml directly, without a per-cell resolve step
 * (a reusable workflow can't be `uses:`-called after a `run:` in the same cell). The image set is derived
 * from the docker/images `.env` files (1:1 with the compose services); a contract test binds the matrix
 * to the images.yaml e2e cells. With `--channel` set, each row also carries a space-joined `tags` field
 * (bare names from [ResolveTagsCommand]) so the nightly workflow doesn't need a per-cell resolve-tags call.
 */
class ResolvePublishMatrixCommand(
    private val imagesDir: Path,
    private val clangVersions: Path,
    private val rubyVersions: Path,
    private val meta: ResolveImageMetaCommand,
    private val tags: ResolveTagsCommand,
) : CliktCommand(name = "resolve-publish-matrix") {
    private val channel by option("--channel").default("")
    private val id by option("--id").default("")

    override fun run() {
        val payload = if (channel.isEmpty()) rows() else rowsWithTags(channel, id)
        echo(ObjectMapper().writeValueAsString(payload))
    }

    fun rowsWithTags(
        channel: String,
        id: String,
    ): List<Map<String, String>> =
        rows().map { row ->
            val resolved = tags.resolve(row.getValue("image"), row.getValue("version"), channel, id)
            row + ("tags" to resolved.joinToString(" "))
        }

    fun rows(): List<Map<String, String>> =
        images().flatMap { image ->
            (listOf("") + nonDefaultVersions(image)).map { version ->
                val m = meta.resolve(image, version)
                mapOf(
                    "image" to image,
                    "version" to version,
                    "token_gated" to m.tokenGated.toString(),
                    "feed_required" to m.feedRequired.toString(),
                    "compose_files" to m.composeFiles,
                )
            }
        }

    private fun images(): List<String> =
        Files.newDirectoryStream(imagesDir, "*.env").use { stream ->
            stream.map { it.fileName.toString().removeSuffix(".env") }.sorted()
        }

    private fun nonDefaultVersions(image: String): List<String> =
        when (image) {
            "qodana-ruby" -> {
                val rows = versionRows(rubyVersions, 2, 3)
                rows.filter { it.getOrNull(2) != "default" }.map { it[0] }
            }
            "qodana-cpp", "qodana-clang" -> {
                val default = meta.resolve(image, "").effectiveVersion
                versionRows(clangVersions, 2, 2).map { it[0] }.filter { it != default }
            }
            else -> emptyList()
        }
}

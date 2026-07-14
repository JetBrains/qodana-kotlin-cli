package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Files
import java.nio.file.Path

data class ImageMeta(
    val tokenGated: Boolean,
    val feedRequired: Boolean,
    val composeFiles: String,
    val effectiveVersion: String,
)

/**
 * The up-front publish gate for one (image, version) cell — single-sources the per-image gating so the
 * dispatch and nightly can't drift from images.yaml (PublishWorkflowContractTest binds this to the matrix).
 * Validates the pair loudly (unknown image → throw; a stray/unknown version → throw via [RuntimeResolver])
 * BEFORE any build starts, and emits the NORMALIZED `effective_version` (the resolved runtime, e.g. "20"
 * for the cpp default) so callers key the concurrency group and tags on it — collapsing an empty version
 * and its explicit default to one identity.
 */
class ResolveImageMetaCommand(
    private val imagesDir: Path,
    private val runtime: RuntimeResolver,
) : CliktCommand(name = "resolve-image-meta") {
    private val image by option("--image").required()
    private val version by option("--version").default("")

    companion object {
        const val INTERNAL_FEED = "https://packages.jetbrains.team/files/p/sa/qodana-dist-internal/feed"
        val TOKEN_GATED = setOf("qodana-clang", "qodana-cdnet")
        const val COMPOSE_FILES =
            "-f linter-images/compose.yaml -f linter-images/compose.ci.yaml -f linter-images/compose.private.yaml"
    }

    override fun run() {
        val m = resolve(image, version)
        echo("token_gated=${m.tokenGated}")
        echo("feed_required=${m.feedRequired}")
        echo("compose_files=${m.composeFiles}")
        echo("effective_version=${m.effectiveVersion}")
    }

    fun resolve(
        image: String,
        version: String,
    ): ImageMeta {
        val envFile = imagesDir.resolve("$image.env")
        check(Files.exists(envFile)) { "unknown image '$image' (no $envFile)" }
        // Validates the version axis (stray on non-versioned, or unknown → throws) and normalizes it.
        val effective = runtime.resolve(image, version)?.version ?: ""
        val feed =
            Files
                .readAllLines(envFile)
                .map { it.substringBefore('#').trim() }
                .firstOrNull { it.startsWith("QD_DISTRIBUTION_FEED=") }
                ?.substringAfter("=")
                ?.trim()
        return ImageMeta(image in TOKEN_GATED, feed == INTERNAL_FEED, COMPOSE_FILES, effective)
    }
}

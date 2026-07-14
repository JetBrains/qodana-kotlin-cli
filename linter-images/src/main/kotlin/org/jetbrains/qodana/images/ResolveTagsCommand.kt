package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Files
import java.nio.file.Path

/**
 * Emits the registry tag list for one (image, version, channel, id) cell, single-sourcing the tag grammar
 * `<mm>-<channel>[.<id>][-<runtime>]`. `<mm>` is major.minor of gradle.properties (fails on `dev`).
 * An empty `id` yields the moving tag (no `.id`); a non-empty `id` the exact tag. A versioned DEFAULT cell
 * dual-tags: the bare tag plus the explicit `-<tool><version>` tag pointing at the same manifest.
 */
class ResolveTagsCommand(
    private val gradleProperties: Path,
    private val runtime: RuntimeResolver,
) : CliktCommand(name = "resolve-tags") {
    private val image by option("--image").required()
    private val version by option("--version").default("")
    private val registry by option("--registry").required()
    private val channel by option("--channel").required()
    private val id by option("--id").default("")

    override fun run() = resolve(image, version, registry, channel, id).forEach { echo(it) }

    fun resolve(
        image: String,
        version: String,
        registry: String,
        channel: String,
        id: String,
    ): List<String> {
        check(channel == "snapshot" || channel == "nightly") { "unknown channel '$channel' (snapshot|nightly)" }
        val base = "${majorMinor()}-$channel" + if (id.isNotEmpty()) ".$id" else ""
        val repo = "$registry/$image"
        val rt = runtime.resolve(image, version)
        return when {
            rt == null -> listOf("$repo:$base")
            rt.isDefault -> listOf("$repo:$base", "$repo:$base-${rt.tool}${rt.version}")
            else -> listOf("$repo:$base-${rt.tool}${rt.version}")
        }
    }

    private fun majorMinor(): String {
        val v =
            Files
                .readAllLines(gradleProperties)
                .map { it.trim() }
                .firstOrNull { !it.startsWith("#") && Regex("^version\\s*=").containsMatchIn(it) }
                ?.substringAfter("=")
                ?.trim()
                ?: error("no version= in $gradleProperties")
        check(v != "dev") { "version=dev; bump gradle.properties to a numeric version before publishing images" }
        val segs = v.split(".")
        check(segs.size >= 2 && segs[0].toIntOrNull() != null && segs[1].toIntOrNull() != null) {
            "version '$v' is not a numeric <major>.<minor>[.<patch>]"
        }
        return "${segs[0]}.${segs[1]}"
    }
}

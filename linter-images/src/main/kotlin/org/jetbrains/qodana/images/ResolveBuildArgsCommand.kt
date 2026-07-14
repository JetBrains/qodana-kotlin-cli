package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Path

/**
 * Resolves a CI matrix (image, version) cell into `--build-arg` tokens + the expected runtime tool/version
 * for the post-build guard. The (tool, effective version, is-default) decision is delegated to the shared
 * [RuntimeResolver]; this command only maps a NON-default runtime to its build args: ruby →
 * ruby-versions.txt QD_BASE_IMAGE; cpp/clang → clang-versions.txt (OS) + debian-bases.txt (base) → CLANG +
 * CLANG_OS + QD_BASE_IMAGE (cpp.dockerfile derives libicu from CLANG_OS). Non-versioned images and the
 * family default emit no build args (the `.env` default already builds them) → EXPECT_TOOL=none skips the guard.
 */
class ResolveBuildArgsCommand(
    imagesDir: Path,
    private val clangVersions: Path,
    private val rubyVersions: Path,
    private val debianBases: Path,
) : CliktCommand(name = "resolve-build-args") {
    private val runtime = RuntimeResolver(imagesDir, clangVersions, rubyVersions)
    private val image by option("--image").required()
    private val version by option("--version").default("")

    data class Resolution(
        val buildArgs: List<String>,
        val expectTool: String,
        val expectVersion: String,
    )

    override fun run() {
        val r = resolve(image, version)
        echo("BUILD_ARGS=${r.buildArgs.joinToString(" ")}")
        echo("EXPECT_TOOL=${r.expectTool}")
        echo("EXPECT_VERSION=${r.expectVersion}")
    }

    fun resolve(
        image: String,
        version: String,
    ): Resolution {
        // A stray version on a non-versioned family, or an unknown version, throws here (loud, never silent).
        val rt = runtime.resolve(image, version) ?: return Resolution(emptyList(), "none", "")
        val args =
            when {
                rt.isDefault -> emptyList()
                rt.tool == "ruby" -> {
                    val row = versionRows(rubyVersions, 2, 3).single { it[0] == rt.version }
                    listOf("--build-arg", "QD_BASE_IMAGE=${row[1]}")
                }
                rt.tool == "clang" -> {
                    val os = versionRows(clangVersions, 2, 2).single { it[0] == rt.version }[1]
                    val base =
                        versionRows(debianBases, 2, 2).singleOrNull { it[0] == os }?.get(1)
                            ?: error("no debian-bases row for '$os'")
                    listOf(
                        "--build-arg",
                        "CLANG=${rt.version}",
                        "--build-arg",
                        "CLANG_OS=$os",
                        "--build-arg",
                        "QD_BASE_IMAGE=$base",
                    )
                }
                else -> error("unexpected runtime tool '${rt.tool}'")
            }
        return Resolution(args, rt.tool, rt.version)
    }
}

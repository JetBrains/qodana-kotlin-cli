package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts the single pushed manifest digest from a `docker push` log. Requires EXACTLY one
 * `digest: sha256:<64 hex>` occurrence — zero or many fails loud, so an ambiguous/empty push output can't
 * silently hand a wrong digest to `imagetools create`. (Kept out of inline workflow bash so it is unit-tested.)
 */
class ExtractDigestCommand(
    private val getContent: (Path) -> String = { Files.readString(it) },
) : CliktCommand(name = "extract-digest") {
    private val file by option("--file").required()

    override fun run() = echo(extract(getContent(Path.of(file))))

    companion object {
        private val DIGEST = Regex("""digest: (sha256:[0-9a-f]{64})\b""")

        fun extract(pushLog: String): String {
            val matches = DIGEST.findAll(pushLog).map { it.groupValues[1] }.toList()
            check(matches.size == 1) {
                "expected exactly one 'digest: sha256:<64hex>' line in the push output, found ${matches.size}"
            }
            return matches.single()
        }
    }
}

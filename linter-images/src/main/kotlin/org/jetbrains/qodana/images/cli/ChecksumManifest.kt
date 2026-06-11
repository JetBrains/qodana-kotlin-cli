package org.jetbrains.qodana.images.cli

/** Parsed `checksums.txt`: maps each asset filename to its hex sha256. */
class ChecksumManifest private constructor(private val byName: Map<String, String>) {
    fun sha256For(fileName: String): String =
        requireNotNull(byName[fileName]) {
            "No checksum for '$fileName' in manifest (have: ${byName.keys.sorted()})"
        }

    companion object {
        fun parse(text: String): ChecksumManifest {
            val byName =
                text
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .associate { line ->
                        val parts = line.split(Regex("\\s+"), limit = 2)
                        require(parts.size == 2) { "Malformed checksum line: '$line'" }
                        // sha256sum binary mode prefixes the name with '*'.
                        val name = parts[1].removePrefix("*")
                        name to parts[0].lowercase()
                    }
            return ChecksumManifest(byName)
        }
    }
}

package org.jetbrains.qodana.images.dist

/** A fully-resolved distribution: the archive Link + its `.sha256` ChecksumLink. */
data class ResolvedDist(
    val link: String,
    val checksumLink: String,
)

class MissingDownloadException(
    message: String,
) : RuntimeException(message)

/** Pure resolution of a [Release]'s `Downloads[osKey]` into a [ResolvedDist] for the given (os, arch). */
object DistResolver {
    fun resolve(
        release: Release,
        os: String,
        arch: String,
    ): ResolvedDist {
        val osKey = osKey(os, arch)
        val download =
            release.downloads?.get(osKey)
                ?: throw MissingDownloadException(
                    "Release ${release.build} has no '$osKey' download " +
                        "(have: ${release.downloads?.keys ?: emptySet<String>()})",
                )
        if (download.link.isEmpty() || download.checksumLink.isEmpty()) {
            throw MissingDownloadException(
                "Release ${release.build} '$osKey' download is missing Link or ChecksumLink",
            )
        }
        return ResolvedDist(link = download.link, checksumLink = download.checksumLink)
    }

    private fun osKey(
        os: String,
        arch: String,
    ): String =
        when {
            os == "linux" && arch == "amd64" -> "linux"
            os == "linux" && arch == "arm64" -> "linuxARM64"
            else -> throw MissingDownloadException("Unsupported os/arch: $os/$arch")
        }
}

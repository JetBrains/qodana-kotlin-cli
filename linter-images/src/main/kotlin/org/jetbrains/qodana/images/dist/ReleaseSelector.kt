package org.jetbrains.qodana.images.dist

class NoSuchReleaseException(
    val code: String,
    val majorVersion: String,
    val build: String,
) : RuntimeException(
        "No release with MajorVersion '$majorVersion' and Build '$build' in feed '$code'",
    )

/** Pure selection: the SINGLE release with MajorVersion == [majorVersion] AND Build == [build] (EXACT pin). */
object ReleaseSelector {
    fun select(
        feed: ProductFeed,
        majorVersion: String,
        build: String,
    ): Release =
        feed.releases
            .firstOrNull { it.majorVersion == majorVersion && it.build == build }
            ?: throw NoSuchReleaseException(feed.code, majorVersion, build)
}

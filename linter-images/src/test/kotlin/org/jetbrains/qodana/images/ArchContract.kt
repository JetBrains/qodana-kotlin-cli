package org.jetbrains.qodana.images

/** The linter images that build multiarch (amd64 + arm64). Single source of truth; QD-15171 grows it. */
object ArchContract {
    val archCapable =
        setOf(
            "qodana-jvm",
            "qodana-jvm-community",
            "qodana-js",
            "qodana-go",
            "qodana-php",
            "qodana-python",
            "qodana-python-community",
            "qodana-ruby",
            "qodana-rust",
            "qodana-dotnet",
            "qodana-android",
            "qodana-android-community",
            "qodana-cpp",
        )
}

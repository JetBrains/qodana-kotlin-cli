pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        maven("https://packages.jetbrains.team/maven/p/sa/static-analysis")
        maven("https://packages.jetbrains.team/maven/p/apl/product-analytics-platform-public")
    }
}

rootProject.name = "qodana-kotlin-cli"

include(
    "qodana-core",
    "qodana-engine",
    "qodana-cli",
    "qodana-clang",
    "qodana-cdnet",
)

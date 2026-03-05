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
    "qodana-app",
    "qodana-infra-process",
    "qodana-infra-gitcli",
    "qodana-infra-http",
    "qodana-infra-dockerjava",
    "qodana-infra-terminal",
    "qodana-infra-fs",
    "qodana-infra-sarif",
    "qodana-infra-tokenstore",
    "qodana-infra-publisher",
    "qodana-infra-fuser",
    "qodana-infra-reportconverter",
    "qodana-cli",
    "qodana-clang",
    "qodana-cdnet",
)

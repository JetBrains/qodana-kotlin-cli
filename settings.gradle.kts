pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Allows Gradle's toolchain machinery to auto-download a GraalVM CE JDK matching
// the per-module `JvmVendorSpec.GRAAL_VM` toolchain pin when the developer's
// JAVA_HOME doesn't already point at one. See CONTRIBUTING.md for the pin.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
    "release-tools",
)

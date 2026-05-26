plugins {
    id("kotlin-common")
    id("testing")
    id("graalvm-native")
    id("qodana-buildinfo")
    application
}

// Pin GraalVM as the toolchain vendor so foojay (settings.gradle.kts) downloads
// GraalVM CE 21 when JAVA_HOME doesn't already point at one. Default Temurin
// toolchains have no `native-image`. When Phase E (QD-14725) adds
// nativeCompile for qodana-clang and qodana-cdnet, the same block needs to
// land there too — the build-logic precompiled plugin can't host the pin
// because the `java { }` DSL accessor isn't available in that context.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.GRAAL_VM)
    }
}

// `project.version` is inherited from root `gradle.properties` (`version=dev` by default; overridden
// via `-Pversion=...` from CI). The `qodana-buildinfo` convention plugin emits a `BuildInfo.kt` source
// with `const val VERSION = "<project.version>"` so it bakes into the native image as a compile-time
// constant. See build-logic/src/main/kotlin/qodana-buildinfo.gradle.kts.
qodanaBuildInfo {
    packageName.set("org.jetbrains.qodana.cli")
}

application {
    mainClass.set("org.jetbrains.qodana.cli.MainKt")
    applicationDefaultJvmArgs =
        listOf(
            "--enable-native-access=ALL-UNNAMED",
        )
}

dependencies {
    implementation(project(":qodana-core"))
    implementation(project(":qodana-engine"))
    implementation(libs.clikt)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.qodana.sarif)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

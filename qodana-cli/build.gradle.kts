plugins {
    id("kotlin-common")
    id("testing")
    id("graalvm-native")
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

// Phase A: hardcoded snapshot. Real per-commit / per-tag versioning lands in
// Phase C (QD-14721) via git describe + a Gradle property.
version = "2026.2-SNAPSHOT"

// Generate a BuildInfo.kt source file at configure time so the project version
// is compile-time-constant and works under both JVM and native-image without
// depending on `-Dqodana.version` propagation through GraalVM class-init order.
val generatedSrcDir = layout.buildDirectory.dir("generated/sources/buildinfo/kotlin/main")
val generateBuildInfo by tasks.registering {
    val versionString = project.version.toString()
    val outFile = generatedSrcDir.map { it.file("org/jetbrains/qodana/cli/BuildInfo.kt") }
    inputs.property("version", versionString)
    outputs.file(outFile)
    doLast {
        val target = outFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            |// Generated — do not edit. Source: qodana-cli/build.gradle.kts
            |package org.jetbrains.qodana.cli
            |
            |internal object BuildInfo {
            |    const val VERSION: String = "$versionString"
            |}
            |
            """.trimMargin(),
        )
    }
}

sourceSets {
    named("main") {
        kotlin.srcDir(generateBuildInfo.map { generatedSrcDir })
    }
}

application {
    mainClass.set("org.jetbrains.qodana.cli.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED"
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

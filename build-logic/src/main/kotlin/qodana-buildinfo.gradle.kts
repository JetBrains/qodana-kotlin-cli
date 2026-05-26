// Convention plugin that generates a `BuildInfo.kt` source file at task-execution time, before
// `compileKotlin` runs. The generated `internal object BuildInfo { const val VERSION = "..." }` makes the
// project version a compile-time constant on both JVM and native-image, sidestepping GraalVM class-init
// ordering for any runtime read of `qodana.version` system property / env var.
//
// Applied to each binary module (qodana-cli, qodana-clang, qodana-cdnet). Each consumer configures the
// target package via the `qodanaBuildInfo { packageName.set("...") }` extension.
//
// History: Phase A (QD-14643) introduced this for qodana-cli only as an inline block. Phase C (QD-14721)
// extracted it here to apply uniformly across all three binary modules.

plugins {
    // The `kotlin("jvm")` apply gives this precompiled script access to the type-safe `sourceSets`
    // accessor and the Kotlin source-set extension. Modules also apply `kotlin-common` which applies
    // `kotlin("jvm")` itself — duplicate applies are idempotent, and it lets `qodana-buildinfo` work
    // standalone in TestKit fixtures that don't apply `kotlin-common`.
    kotlin("jvm")
}

interface QodanaBuildInfoExtension {
    val packageName: Property<String>
}

val ext = extensions.create<QodanaBuildInfoExtension>("qodanaBuildInfo")

val generatedSrcDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/sources/buildinfo/kotlin/main")

val generateBuildInfo by tasks.registering {
    val versionString = project.version.toString()
    val packageProvider = ext.packageName
    val outDir = generatedSrcDir
    inputs.property("version", versionString)
    inputs.property("package", packageProvider)
    outputs.dir(outDir)
    doLast {
        val pkg = packageProvider.get()
        val packagePath = pkg.replace('.', '/')
        val target = outDir.get().file("$packagePath/BuildInfo.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            |// Generated — do not edit. Source: build-logic/src/main/kotlin/qodana-buildinfo.gradle.kts
            |package $pkg
            |
            |internal object BuildInfo {
            |    const val VERSION: String = "$versionString"
            |}
            |
            """.trimMargin(),
        )
    }
}

sourceSets.named("main") {
    kotlin.srcDir(generatedSrcDir)
}
tasks.named("compileKotlin").configure { dependsOn(generateBuildInfo) }

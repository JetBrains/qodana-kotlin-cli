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

// `generateBuildInfo` writes a BuildInfo.kt source file at task-execution time
// before `compileKotlin` runs. The generated `const val BuildInfo.VERSION` makes
// the project version a compile-time constant on both JVM and native-image,
// sidestepping GraalVM class-init ordering for `-Dqodana.version`.
val generatedSrcDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/sources/buildinfo/kotlin/main")

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

sourceSets.named("main") {
    kotlin.srcDir(generatedSrcDir)
}
tasks.named("compileKotlin").configure { dependsOn(generateBuildInfo) }

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

// =====

/**
 * Parses the canonical banned-patterns file consumed by both this strip task
 * and [`MetadataHygieneTest`](src/test/kotlin/org/jetbrains/qodana/cli/MetadataHygieneTest.kt).
 *
 * Format: `# ...` is a comment; blank lines are ignored; section markers are
 * `[class-prefixes]` and `[resource-substrings]`. The same parser logic lives
 * in `BannedMetadataPatterns.kt` (test source); the duplication is unavoidable
 * because a Gradle script can't import a test class. Any change to the parse
 * rules must be mirrored.
 */
fun loadBannedMetadataPatterns(file: java.io.File): Pair<List<String>, List<String>> {
    require(file.exists()) { "banned-metadata-patterns.txt is missing at ${file.absolutePath}" }
    val classPrefixes = mutableListOf<String>()
    val resourceSubstrings = mutableListOf<String>()
    var section: MutableList<String>? = null
    file.readLines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        when (line) {
            "[class-prefixes]" -> section = classPrefixes
            "[resource-substrings]" -> section = resourceSubstrings
            else -> {
                checkNotNull(section) {
                    "banned-metadata-patterns.txt: entry \"$line\" appears before any section marker"
                }.add(line)
            }
        }
    }
    check(classPrefixes.isNotEmpty()) {
        "banned-metadata-patterns.txt: [class-prefixes] section is empty or missing"
    }
    check(resourceSubstrings.isNotEmpty()) {
        "banned-metadata-patterns.txt: [resource-substrings] section is empty or missing"
    }
    return classPrefixes to resourceSubstrings
}

val bannedMetadataPatternsFile = file("src/test/resources/banned-metadata-patterns.txt")
val (testClassPrefixes, testResourceSubstrings) = loadBannedMetadataPatterns(bannedMetadataPatternsFile)

/**
 * Class-name prefixes for which we promote `queryAllDeclared{Methods,Constructors}`
 * to `allDeclared{Methods,Constructors}`. The tracing agent records the strongest
 * mode it actually saw (e.g. `queryAll` if code called `Class.getMethods()`); but
 * docker-java's Jackson deserialiser invokes constructors and methods reflectively
 * at runtime via `Constructor.newInstance` + `Method.invoke`, which requires the
 * stronger `allDeclared*` form. On a developer machine with a logged-in Docker
 * daemon the agent typically sees constructor invocation directly and emits
 * `allDeclared*`; on a CI runner with no Docker auth, the agent only sees the
 * query path. Promoting these prefixes covers both cases reliably.
 */
val reflectivelyInvokedPackagePrefixes =
    listOf(
        "com.github.dockerjava.api.",
        "com.github.dockerjava.core.",
    )

/**
 * Removes test-infrastructure entries from every GraalVM native-image metadata
 * JSON file after [metadataCopy] writes them to the source tree, and promotes
 * `queryAllDeclared*` entries for [reflectivelyInvokedPackagePrefixes] classes
 * to the stronger `allDeclared*` form. The task is registered as a finalizer of
 * [metadataCopy] so it runs automatically whenever the agent pipeline produces
 * new metadata.
 *
 * Files modified in-place (relative to the metadata output directory):
 *   - reflect-config.json  — JSON array keyed on "name"
 *   - jni-config.json      — JSON array keyed on "name"
 *   - resource-config.json — JSON object with resources.includes array keyed on "pattern"
 *
 * proxy-config.json, serialization-config.json, and predefined-classes-config.json
 * contain no test-class entries and are left untouched.
 */
val stripTestEntriesFromMetadata by tasks.registering {
    val metadataDir =
        layout.projectDirectory.dir(
            "src/main/resources/META-INF/native-image/org.jetbrains.qodana/${project.name}",
        )
    inputs.dir(metadataDir)
    // Track the canonical pattern list so edits invalidate this task.
    inputs.file(bannedMetadataPatternsFile)
    outputs.dir(metadataDir)

    doLast {
        val slurper = groovy.json.JsonSlurper()

        // strip-config.json helpers =====

        fun isTestName(name: String): Boolean = testClassPrefixes.any { name.startsWith(it) }

        fun isTestPattern(pattern: String): Boolean = testResourceSubstrings.any { it in pattern }

        fun List<*>.toJson(): String = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(this))

        fun Map<*, *>.toJson(): String = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(this))

        fun isReflectivelyInvoked(name: String): Boolean = reflectivelyInvokedPackagePrefixes.any { name.startsWith(it) }

        // For docker-java DTOs the agent often records only `queryAllDeclared*`
        // (introspection via Class.getMethods()), but Jackson + docker-java
        // actually invoke constructors and methods via Constructor.newInstance()
        // and Method.invoke(). Promote query-only entries to the full
        // `allDeclared*` form so the native binary doesn't throw
        // MissingReflectionRegistrationError on CI runners where the agent
        // happened not to see the invocation path locally.
        fun promoteReflection(entry: Map<String, Any>): Map<String, Any> {
            val name = entry["name"]?.toString().orEmpty()
            if (!isReflectivelyInvoked(name)) return entry
            val updated = entry.toMutableMap()
            if (updated["queryAllDeclaredConstructors"] == true && updated["allDeclaredConstructors"] != true) {
                updated["allDeclaredConstructors"] = true
            }
            if (updated["queryAllDeclaredMethods"] == true && updated["allDeclaredMethods"] != true) {
                updated["allDeclaredMethods"] = true
            }
            return updated
        }

        // reflect-config.json -----
        val reflectFile = metadataDir.file("reflect-config.json").asFile
        if (reflectFile.exists()) {
            @Suppress("UNCHECKED_CAST")
            val entries = slurper.parse(reflectFile) as List<Map<String, Any>>
            val stripped = entries.filterNot { isTestName(it["name"]?.toString().orEmpty()) }
            val promoted = stripped.map(::promoteReflection)
            val promotionsCount =
                promoted.zip(stripped).count { (after, before) -> after != before }
            if (stripped.size != entries.size || promotionsCount > 0) {
                reflectFile.writeText(promoted.toJson())
                if (stripped.size != entries.size) {
                    logger.lifecycle(
                        "stripTestEntriesFromMetadata: removed {} test entries from reflect-config.json",
                        entries.size - stripped.size,
                    )
                }
                if (promotionsCount > 0) {
                    logger.lifecycle(
                        "stripTestEntriesFromMetadata: promoted {} docker-java DTOs to allDeclared{{Methods,Constructors}}",
                        promotionsCount,
                    )
                }
            }
        }

        // jni-config.json -----
        val jniFile = metadataDir.file("jni-config.json").asFile
        if (jniFile.exists()) {
            @Suppress("UNCHECKED_CAST")
            val entries = slurper.parse(jniFile) as List<Map<String, Any>>
            val stripped = entries.filterNot { isTestName(it["name"]?.toString().orEmpty()) }
            if (stripped.size != entries.size) {
                jniFile.writeText(stripped.toJson())
                logger.lifecycle(
                    "stripTestEntriesFromMetadata: removed {} test entries from jni-config.json",
                    entries.size - stripped.size,
                )
            }
        }

        // resource-config.json -----
        val resourceFile = metadataDir.file("resource-config.json").asFile
        if (resourceFile.exists()) {
            @Suppress("UNCHECKED_CAST")
            val root = slurper.parse(resourceFile) as Map<String, Any>

            @Suppress("UNCHECKED_CAST")
            val resources = root["resources"] as? Map<String, Any> ?: emptyMap()

            @Suppress("UNCHECKED_CAST")
            val includes = resources["includes"] as? List<Map<String, Any>> ?: emptyList()
            val stripped = includes.filterNot { isTestPattern(it["pattern"]?.toString().orEmpty()) }
            if (stripped.size != includes.size) {
                val updated: Map<String, Any> =
                    mapOf(
                        "resources" to mapOf("includes" to stripped),
                        "bundles" to (root["bundles"] ?: emptyList<Any>()),
                    )
                resourceFile.writeText(updated.toJson())
                logger.lifecycle(
                    "stripTestEntriesFromMetadata: removed {} test entries from resource-config.json",
                    includes.size - stripped.size,
                )
            }
        }
    }
}

tasks.named("metadataCopy").configure {
    finalizedBy(stripTestEntriesFromMetadata)
}

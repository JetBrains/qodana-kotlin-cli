plugins {
    id("kotlin-common")
    id("testing")
    id("graalvm-native")
    id("qodana-buildinfo")
    id("qodana-release")
    application
}

// Pin GraalVM vendor so foojay downloads it when JAVA_HOME points elsewhere.
// Default Temurin toolchains have no `native-image`.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.GRAAL_VM)
    }
}

qodanaBuildInfo {
    packageName.set("org.jetbrains.qodana.cli")
}

qodanaRelease {
    kind.set(QodanaReleaseKind.Cli)
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
    // QD-14812: NativeWindowsDepsTest delegates to the shared assertion in qodana-core's
    // testFixtures. PortEx is pulled in transitively from there.
    testImplementation(testFixtures(project(":qodana-core")))
}

// =====

// Mirrored in `BannedMetadataPatterns.kt` (test source); a Gradle script
// can't import a test class, so any change to the format goes in both.
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

// docker-java's Jackson chain invokes constructors and methods via
// Constructor.newInstance/Method.invoke at runtime, but the agent only
// captures the `queryAll*` (introspection) variant on CI runners without
// warm Docker auth. Promote these prefixes to `allDeclared*` so the native
// binary works both locally and in CI.
val reflectivelyInvokedPackagePrefixes =
    listOf(
        "com.github.dockerjava.api.",
        "com.github.dockerjava.core.",
    )

// Strips test-infrastructure entries from the committed metadata JSON and
// promotes the docker-java prefixes above. Wired as a finalizer of
// `metadataCopy` so it runs automatically on every agent regen.
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

import org.gradle.api.tasks.testing.Test

plugins {
}

fun loadDotEnv(path: java.io.File): Map<String, String> {
    if (!path.exists() || !path.isFile) return emptyMap()
    return path.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { raw ->
            val line = raw.removePrefix("export ").trim()
            val idx = line.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            if (key.isEmpty()) null else key to value
        }
        .toMap()
}

val dotEnvValues: Map<String, String> by lazy {
    loadDotEnv(rootProject.file(".env"))
}

fun envOrDotEnv(name: String): String? {
    val fromEnv = System.getenv(name)?.trim()
    if (!fromEnv.isNullOrEmpty()) return fromEnv
    val fromDotEnv = dotEnvValues[name]?.trim()
    return fromDotEnv?.takeIf { it.isNotEmpty() }
}

subprojects {
    tasks.withType<Test>().configureEach {
        val qodanaToken = envOrDotEnv("QODANA_TOKEN")
        val licenseOnlyToken = envOrDotEnv("QODANA_LICENSE_ONLY_TOKEN") ?: qodanaToken

        if (!qodanaToken.isNullOrBlank()) {
            environment("QODANA_TOKEN", qodanaToken)
        }
        if (!licenseOnlyToken.isNullOrBlank()) {
            environment("QODANA_LICENSE_ONLY_TOKEN", licenseOnlyToken)
        }
    }

    // Default `test` task: skip Docker-tagged tests so a contributor can
    // run `./gradlew test` without Docker installed, and skip
    // `native-binary`-tagged tests because they need a prebuilt native
    // binary supplied via -D flags (only available in CI).
    plugins.withId("java") {
        tasks.named<Test>("test") {
            useJUnitPlatform {
                excludeTags("docker", "native-binary")
            }
        }
    }

    tasks.register<Test>("parityTest") {
        group = "verification"
        description = "Runs Docker-tagged integration tests (requires a running Docker daemon)."

        val testTask = tasks.named<Test>("test").get()
        testClassesDirs = testTask.testClassesDirs
        classpath = testTask.classpath
        // Run ONLY @Tag("docker") tests. They fail loudly if Docker is
        // unreachable, per CLAUDE.md "tests must never silently skip".
        useJUnitPlatform {
            includeTags("docker")
        }
        shouldRunAfter(testTask)

        environment("QODANA_TEST_CONTAINER", envOrDotEnv("QODANA_TEST_CONTAINER") ?: "1")
        val qodanaToken = envOrDotEnv("QODANA_TOKEN")
        val licenseOnlyToken = envOrDotEnv("QODANA_LICENSE_ONLY_TOKEN") ?: qodanaToken
        if (!qodanaToken.isNullOrBlank()) {
            environment("QODANA_TOKEN", qodanaToken)
        }
        if (!licenseOnlyToken.isNullOrBlank()) {
            environment("QODANA_LICENSE_ONLY_TOKEN", licenseOnlyToken)
        }
    }

    tasks.register<Test>("nativeBinaryTest") {
        group = "verification"
        description =
            "Runs @Tag(\"native-binary\") tests against a prebuilt qodana-cli native binary. " +
                "Requires -Dtest.qodana.binary plus harness-specific -D flags; see " +
                "NativeBinarySendSmokeTest and SarifCompareIntegrationTest for the input list."

        val testTask = tasks.named<Test>("test").get()
        testClassesDirs = testTask.testClassesDirs
        classpath = testTask.classpath
        useJUnitPlatform {
            includeTags("native-binary")
        }
        shouldRunAfter(testTask)

        // Forward the -D test.* system properties from the Gradle invocation to
        // the test JVM, so CI can pass them on a single `./gradlew :qodana-cli:nativeBinaryTest -D...`.
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("test.") }
            .forEach { systemProperty(it, System.getProperty(it)) }
    }
}

tasks.register("parityTest") {
    group = "verification"
    description = "Runs parityTest in all modules."
    dependsOn(subprojects.map { it.tasks.named("parityTest") })
}

import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kover) apply false
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
                excludeTags("docker", "native-binary", "linter-e2e")
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

    tasks.register<Test>("linterE2eTest") {
        group = "verification"
        description =
            "Runs @Tag(\"linter-e2e\") Docker-image e2e tests against a prebuilt qodana-<x>:dev image. " +
                "Requires a running Docker daemon and -Dlinter.e2e.image=<qodana-jvm|qodana-android|" +
                "qodana-clang>; see LinterE2eTest for the fixture discovery contract."

        val testTask = tasks.named<Test>("test").get()
        testClassesDirs = testTask.testClassesDirs
        classpath = testTask.classpath
        // Run ONLY @Tag("linter-e2e") tests. They fail loudly if Docker is
        // unreachable, per CLAUDE.md "tests must never silently skip".
        useJUnitPlatform {
            includeTags("linter-e2e")
        }
        shouldRunAfter(testTask)

        // Forward -Dlinter.e2e.* (image selector etc.) from the Gradle invocation
        // to the test JVM, so CI passes them on a single `./gradlew
        // :linter-images:linterE2eTest -Dlinter.e2e.image=<image>`.
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("linter.e2e.") }
            .forEach { systemProperty(it, System.getProperty(it)) }

        // Qodana token: the scan may license-check / upload; forward like parityTest.
        val qodanaToken = envOrDotEnv("QODANA_TOKEN")
        val licenseOnlyToken = envOrDotEnv("QODANA_LICENSE_ONLY_TOKEN") ?: qodanaToken
        if (!qodanaToken.isNullOrBlank()) {
            environment("QODANA_TOKEN", qodanaToken)
        }
        if (!licenseOnlyToken.isNullOrBlank()) {
            environment("QODANA_LICENSE_ONLY_TOKEN", licenseOnlyToken)
        }
    }
}

tasks.register("parityTest") {
    group = "verification"
    description = "Runs parityTest in all modules."
    dependsOn(subprojects.map { it.tasks.named("parityTest") })
}

// Aggregate coverage from every module so a module's tests count toward classes they exercise in
// dependency modules (e.g. qodana-engine tests → qodana-core coverage). Derived from subprojects so a
// newly added module can't be silently omitted. Per Kover: without kover(project) deps a report covers
// only the current project. Gate (koverEnabled) is computed once in settings.gradle.kts.
if (extra["koverEnabled"] as Boolean) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    dependencies {
        subprojects.forEach { add("kover", project(it.path)) }
    }
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        reports {
            total {
                binary {
                    // Qodana ingests this IntelliJ-Coverage .ic.
                    file.set(layout.buildDirectory.file("reports/kover/report.ic"))
                    onCheck.set(false)
                }
            }
        }
    }
}

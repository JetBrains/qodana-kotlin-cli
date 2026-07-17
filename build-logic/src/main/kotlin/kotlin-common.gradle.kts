import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
    id("kotlin-ktlint")
    id("kotlin-detekt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Keep Kotlin language/api version on plugin defaults.
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn",
            // KT-73255 (Kotlin 2.3): keep the pre-2.3 default of applying a no-use-site-target
            // annotation on a constructor property to the value parameter only. Preserves the
            // current Jackson/native-image behavior; adopting `param-property` is a separate,
            // deliberately tested change, not part of the JDK 21->25 toolchain migration.
            "-Xannotation-default-target=first-only",
        )
    }
}

// -Pkover-gated (koverEnabled computed once in settings.gradle.kts): Kover instruments the `test` task
// itself, so gating by application — not just report tasks — is what keeps plain `./gradlew test` unchanged.
if (rootProject.extra["koverEnabled"] as Boolean) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        currentProject {
            instrumentation {
                // Keep :koverBinaryReport to the unit `test` task. These need Docker / a prebuilt native
                // binary and run product code out-of-JVM, so they carry no JVM coverage.
                disabledForTestTasks.addAll("parityTest", "nativeBinaryTest", "linterE2eTest")
            }
        }
    }
}

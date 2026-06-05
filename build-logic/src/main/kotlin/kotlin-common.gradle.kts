import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
    id("kotlin-ktlint")
    id("kotlin-detekt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
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

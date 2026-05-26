plugins {
    `kotlin-dsl`
    `jvm-test-suite`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.graalvm.native.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
        }
    }
}

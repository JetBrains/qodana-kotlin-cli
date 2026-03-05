plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.databind)
    api(libs.jackson.dataformat.yaml)
    api(libs.jackson.module.kotlin)

    // process (SystemProcessRunner) - no extra deps
    // terminal (MordantTerminal)
    implementation(libs.mordant)
    // fs (NioFileSystem, WebUiExtractor)
    implementation(libs.commons.compress)
    // sarif (QodanaSarifService)
    implementation(libs.qodana.sarif)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

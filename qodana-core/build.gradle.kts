plugins {
    id("kotlin-common")
    id("testing")
    // QD-14925: hosts the WindowsNativeDepsAssertion helper shared by the three modules that produce
    // Windows native binaries (qodana-cli, qodana-clang, qodana-cdnet). Their NativeWindowsDepsTest
    // classes delegate to it via `testImplementation(testFixtures(project(":qodana-core")))`.
    `java-test-fixtures`
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

    // QD-14925: test-fixtures consumers (qodana-cli, qodana-clang, qodana-cdnet) get PortEx + the
    // shared WindowsNativeDepsAssertion entrypoint transitively. JUnit Jupiter is on the API surface
    // because the helper exposes a function annotated for JUnit's @Test in caller classes.
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.kotlin.test)
    testFixturesImplementation(libs.portex)
}

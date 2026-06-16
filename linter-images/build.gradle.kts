plugins {
    id("kotlin-common")
    id("testing")
    application
}

application {
    // installDist stages the image-tool for the Docker builder stage (CLI_SOURCE=context / dist provisioning).
    applicationName = "image-tool"
    mainClass.set("org.jetbrains.qodana.images.MainKt")
}

// Pin the test working dir to the module root so guard tests that read fixture files by relative
// path resolve them consistently, with no user.dir fallback. (Shared Contracts → Tests working directory.)
tasks.withType<Test> {
    workingDir = layout.projectDirectory.asFile
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.jackson.dataformat.yaml) // ComposeContractTest parses compose*.yaml
    testImplementation(libs.qodana.sarif) // e2e harness parses qodana.sarif.json with the typed model
}

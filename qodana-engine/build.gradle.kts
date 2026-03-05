plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    api(project(":qodana-core"))
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.databind)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // docker
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.transport.httpclient5)

    // git (uses ProcessRunner from core)

    // http
    implementation(libs.okhttp)

    // publisher
    implementation(libs.qodana.publisher)
    implementation(libs.qodana.cloud.client)

    // fuser
    implementation(libs.fus.reporting.anonymization)
    implementation(libs.fus.reporting.validation)
    implementation(libs.fus.reporting.client)
    implementation(libs.fus.reporting.model)
    implementation(libs.fus.reporting.scheme)
    implementation(libs.fus.metadata.client)
    implementation(libs.ap.scheme.tools)

    // report converter
    implementation(libs.intellij.report.converter)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

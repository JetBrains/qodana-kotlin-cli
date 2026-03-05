plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    api(project(":qodana-core"))
    implementation(libs.fus.reporting.anonymization)
    implementation(libs.fus.reporting.validation)
    implementation(libs.fus.reporting.client)
    implementation(libs.fus.reporting.model)
    implementation(libs.fus.reporting.scheme)
    implementation(libs.fus.metadata.client)
    implementation(libs.ap.scheme.tools)
    implementation(libs.jackson.databind)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

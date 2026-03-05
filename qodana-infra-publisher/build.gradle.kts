plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    api(project(":qodana-core"))
    implementation(libs.qodana.publisher)
    implementation(libs.qodana.cloud.client)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

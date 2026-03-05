plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    api(project(":qodana-core"))
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

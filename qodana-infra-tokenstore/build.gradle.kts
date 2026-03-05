plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    api(project(":qodana-core"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

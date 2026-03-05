plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.jackson.databind)
    api(libs.jackson.dataformat.yaml)
    api(libs.jackson.module.kotlin)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

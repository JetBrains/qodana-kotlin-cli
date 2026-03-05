plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    api(project(":qodana-core"))
    implementation(project(":qodana-infra-process"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

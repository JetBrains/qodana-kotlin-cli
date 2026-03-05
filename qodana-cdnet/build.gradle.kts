plugins {
    id("kotlin-common")
    id("testing")
    id("graalvm-native")
    application
}

application {
    mainClass.set("org.jetbrains.qodana.cdnet.MainKt")
}

dependencies {
    implementation(project(":qodana-core"))
    implementation(project(":qodana-app"))
    implementation(project(":qodana-infra-process"))
    implementation(project(":qodana-infra-terminal"))
    implementation(project(":qodana-infra-fs"))
    implementation(project(":qodana-infra-sarif"))
    implementation(libs.clikt)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
}

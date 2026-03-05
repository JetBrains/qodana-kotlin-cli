plugins {
    id("kotlin-common")
    id("testing")
    id("graalvm-native")
    application
}

application {
    mainClass.set("org.jetbrains.qodana.cli.MainKt")
}

dependencies {
    implementation(project(":qodana-core"))
    implementation(project(":qodana-app"))
    implementation(project(":qodana-infra-process"))
    implementation(project(":qodana-infra-gitcli"))
    implementation(project(":qodana-infra-http"))
    implementation(project(":qodana-infra-dockerjava"))
    implementation(project(":qodana-infra-terminal"))
    implementation(project(":qodana-infra-fs"))
    implementation(project(":qodana-infra-sarif"))
    implementation(project(":qodana-infra-tokenstore"))
    implementation(project(":qodana-infra-publisher"))
    implementation(project(":qodana-infra-fuser"))
    implementation(project(":qodana-infra-reportconverter"))
    implementation(libs.clikt)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

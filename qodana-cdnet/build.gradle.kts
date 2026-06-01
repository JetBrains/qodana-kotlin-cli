plugins {
    id("kotlin-common")
    id("testing")
    id("graalvm-native")
    id("qodana-buildinfo")
    id("qodana-release")
    application
}

qodanaBuildInfo {
    packageName.set("org.jetbrains.qodana.cdnet")
}

qodanaRelease {
    kind.set(QodanaReleaseKind.Tool)
}

application {
    mainClass.set("org.jetbrains.qodana.cdnet.MainKt")
}

dependencies {
    implementation(project(":qodana-core"))
    implementation(project(":qodana-engine"))
    implementation(libs.clikt)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // QD-14812: NativeWindowsDepsTest delegates to the shared assertion in qodana-core's
    // testFixtures. PortEx is pulled in transitively from there.
    testImplementation(testFixtures(project(":qodana-core")))
}

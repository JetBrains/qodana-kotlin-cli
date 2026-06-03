plugins {
    id("kotlin-common")
    id("testing")
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}

// Release-version invariant guard (wired into pre-push). Runs the COMPILED CheckVersionMain so the
// bump-rule logic stays one tested Kotlin source — no Gradle/Kotlin-script duplication, and pushing
// needs no Kotlin compiler.
tasks.register<JavaExec>("checkVersion") {
    group = "verification"
    description = "Validate that gradle.properties version is in a release-eligible state."
    mainClass.set("org.jetbrains.qodana.release.CheckVersionMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("qodana.source", rootProject.version.toString())
    (project.findProperty("requireExact") as String?)?.let { systemProperty("qodana.requireExact", it) }
}

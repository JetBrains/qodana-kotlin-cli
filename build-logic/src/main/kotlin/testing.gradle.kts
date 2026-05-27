import org.gradle.api.tasks.testing.logging.TestExceptionFormat

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        // FULL exception format keeps assertion messages in the test log so CI
        // failures don't require fishing through HTML reports the GitHub runner
        // doesn't expose. Without this, Gradle prints only the class+line of the
        // AssertionFailedError and elides the dump that the assertion lambda
        // built (e.g. "native binary `send` exited 1; output: ...").
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

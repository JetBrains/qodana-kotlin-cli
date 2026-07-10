import org.gradle.api.tasks.testing.logging.TestExceptionFormat

tasks.withType<Test>().configureEach {
    // `native-deps` tag covers tests that need a built `nativeCompile` artifact on
    // disk (e.g. NativeWindowsDepsTest). Excluded by default so `./gradlew test`
    // on a fresh checkout doesn't trip; opt in via `-PnativeTests=true` (CI does
    // this on the CLI workflow's Windows `build` matrix entry).
    val nativeTestsEnabled = project.findProperty("nativeTests") == "true"
    useJUnitPlatform {
        if (!nativeTestsEnabled) {
            excludeTags("native-deps")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        // NativeWindowsDepsTest dumps its `.exe`'s sorted DLL import list into the
        // failure message; showStandardStreams is the only way that survives intact.
        // Gated so regular test logs don't drown in stdout.
        if (nativeTestsEnabled) {
            showStandardStreams = true
        }
    }
}

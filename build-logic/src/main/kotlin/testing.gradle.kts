import org.gradle.api.tasks.testing.logging.TestExceptionFormat

tasks.withType<Test>().configureEach {
    // `native-deps` tag covers tests that need a built `nativeCompile` artifact on
    // disk (e.g. NativeWindowsDepsTest). Excluded by default so `./gradlew test`
    // on a fresh checkout doesn't trip; opt in via `-PnativeTests=true` (CI does
    // this on the Windows native-build matrix entry).
    val nativeTestsEnabled = project.findProperty("nativeTests") == "true"
    useJUnitPlatform {
        if (!nativeTestsEnabled) {
            excludeTags("native-deps")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
        if (nativeTestsEnabled) {
            // FULL + showStandardStreams ensures NativeWindowsDepsTest's failure
            // message — which embeds the produced `.exe`'s sorted DLL import list —
            // reaches the CI log untruncated. That list is the Phase B evidence.
            // Scoped to native-deps runs so the rest of CI's test logs stay quiet.
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }
}

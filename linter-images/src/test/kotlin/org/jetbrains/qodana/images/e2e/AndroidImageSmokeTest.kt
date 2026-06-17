package org.jetbrains.qodana.images.e2e

import org.jetbrains.qodana.images.process.ProcessCommandRunner
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * No-scan image smoke check for the android-family images (QD-1237 / QD-1317 / QD-4285 / QD-15034).
 *
 * Unlike [LinterE2eTest] this produces no SARIF: it execs a shell into the selected image's `:dev`
 * tag and asserts the image's Android SDK + Corretto provisioning is intact (ANDROID_HOME,
 * sdkmanager, platform-tools, Corretto 11 + 17). Different KIND of assertion (image filesystem
 * invariants), so it stays out of the manifest/SARIF pipeline and does not touch DockerRunPlanner /
 * evaluator / manifest.
 *
 * Both `qodana-android` and `qodana-android-community` carry byte-identical provisioning (same
 * `lib/toolchain/android.dockerfile`, same `ANDROID_SDK_*`/`CORRETTO*` `.env` values), so the
 * assertions are shared and only the image tag under test varies.
 *
 * Routing mirrors [LinterE2eTest.discover]: a `@TestFactory` that returns an EMPTY stream unless
 * `-Dlinter.e2e.image` names an android-family image. So the qodana-jvm / qodana-clang cells emit
 * ZERO tests here — no JUnit "skipped/aborted" noise. (We deliberately avoid `assumeTrue`, which
 * reports a skip; an empty stream is the clean per-image routing.)
 */
@Tag("linter-e2e")
class AndroidImageSmokeTest {
    @TestFactory
    fun androidImageSmoke(): Stream<DynamicNode> {
        val image = System.getProperty("linter.e2e.image")
        if (image !in ANDROID_IMAGES) return Stream.empty()
        return Stream.of(
            DynamicTest.dynamicTest("$image image carries the SDK and Corretto layout") {
                requireDocker()
                // Single `docker run` that asserts every invariant, printing which one failed.
                // Exit 0 iff all hold; exit codes 11-15 make a CI failure self-identifying.
                val script =
                    buildString {
                        append("set -e; ")
                        append(
                            "[ \"\$ANDROID_HOME\" = \"$ANDROID_HOME\" ] || " +
                                "{ echo 'ANDROID_HOME='\"\$ANDROID_HOME\"' != $ANDROID_HOME'; exit 11; }; ",
                        )
                        append(
                            "[ -x \"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager\" ] || " +
                                "{ echo 'missing sdkmanager'; exit 12; }; ",
                        )
                        append(
                            "[ -d \"$ANDROID_HOME/platform-tools\" ] || " +
                                "{ echo 'missing platform-tools'; exit 13; }; ",
                        )
                        append("[ -d \"$CORRETTO11\" ] || { echo 'missing $CORRETTO11'; exit 14; }; ")
                        append("[ -d \"$CORRETTO17\" ] || { echo 'missing $CORRETTO17'; exit 15; }; ")
                        append("echo OK")
                    }
                val result =
                    ProcessCommandRunner().run(
                        listOf(
                            "docker",
                            "run",
                            "--rm",
                            "--network",
                            "none",
                            "--entrypoint",
                            "bash",
                            "$image:dev",
                            "-c",
                            script,
                        ),
                    )
                assertEquals(
                    0,
                    result.exitCode,
                    "android image smoke check failed (exit ${result.exitCode}).\n" +
                        "stdout:\n${result.stdout}\nstderr:\n${result.stderr}",
                )
            },
        )
    }

    // Identical to LinterE2eTest.requireDocker() (both fail loudly when Docker is down). Kept local
    // to this no-SARIF test rather than shared, to avoid coupling it to the manifest pipeline.
    private fun requireDocker() {
        val info = ProcessCommandRunner().run(listOf("docker", "info"))
        if (!info.isSuccess) {
            fail("@Tag(\"linter-e2e\") test ran but Docker is unreachable: ${info.stderr.ifBlank { info.stdout }}")
        }
    }

    private companion object {
        // Android-family images: both consume the same lib/toolchain/android.dockerfile with identical
        // ANDROID_SDK_*/CORRETTO* args, so the filesystem invariants below hold for either.
        val ANDROID_IMAGES = setOf("qodana-android", "qodana-android-community")

        // CONFIRMED from linter-images/docker/images/qodana-android{,-community}.dockerfile +
        // lib/toolchain/android.dockerfile (QD-1237 / QD-1317 / QD-4285 / QD-15034).
        const val ANDROID_HOME = "/opt/android-sdk"
        const val CORRETTO11 = "/opt/java/corretto11"
        const val CORRETTO17 = "/opt/java/corretto17"
    }
}

@file:Import("../src/main/kotlin/org/jetbrains/qodana/release/VersionCompute.kt")
@file:Import("../src/main/kotlin/org/jetbrains/qodana/release/VersionFormat.kt")

import org.jetbrains.qodana.release.normalizeReleaseVersion
import kotlin.system.exitProcess

val input = args.getOrNull(0) ?: run {
    System.err.println("usage: kotlin normalize-version.main.kts <version>")
    exitProcess(2)
}
normalizeReleaseVersion(input).fold(
    onSuccess = { println(it) },
    onFailure = { System.err.println("invalid version: ${it.message}"); exitProcess(1) },
)

package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.env.QodanaEnv
import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.port.Terminal
import org.jetbrains.qodana.engine.port.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

class EapChecker(
    private val terminal: Terminal,
    private val isContainer: Boolean = false,
    private val clock: Clock = Clock { Instant.now() },
) {
    data class EapResult(
        val expired: Boolean,
        val message: String?,
        val exitCode: Int = 0,
    )

    fun checkAndPrint(buildDateStr: String, isEap: Boolean): EapResult {
        val result = check(buildDateStr, isEap)
        if (result.message != null) {
            if (result.expired) terminal.error(result.message) else terminal.println(result.message)
        }
        return result
    }

    fun check(buildDateStr: String, isEap: Boolean): EapResult {
        if (!isEap || !System.getenv(QodanaEnv.TREAT_AS_RELEASE).isNullOrBlank()) {
            return EapResult(expired = false, message = null)
        }

        val buildDate = try {
            ZonedDateTime.parse(buildDateStr, DateTimeFormatter.ISO_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            return EapResult(
                expired = true,
                message = "Failed to parse build date",
                exitCode = ExitCode.EAP_EXPIRED.code,
            )
        }

        val deadline = buildDate.plus(60, ChronoUnit.DAYS)
        val now = clock.now()

        return if (now.isAfter(deadline)) {
            val message = if (isContainer) {
                "EAP license of this Qodana image is expired. Please use \"docker pull\" to update image."
            } else {
                "EAP license of this Qodana linter is expired. Obtain the new one with the latest version of Qodana CLI."
            }
            EapResult(expired = true, message = message, exitCode = ExitCode.EAP_EXPIRED.code)
        } else {
            val dateStr = DateTimeFormatter.ofPattern("MMMM dd, yyyy")
                .format(deadline.atZone(java.time.ZoneId.systemDefault()))
            val agreement = if (isContainer) {
                """
                |
                |By using this Docker image, you agree to
                |- JetBrains Privacy Policy (https://jb.gg/jetbrains-privacy-policy)
                |- JETBRAINS EAP USER AGREEMENT (https://jb.gg/jetbrains-user-eap)
                |
                |The Docker image includes an evaluation license.
                |The license will expire on $dateStr.
                |Please ensure you pull a new image on time.
                """.trimMargin()
            } else {
                """
                |
                |By using this linter, you agree to
                |- JetBrains Privacy Policy (https://jb.gg/jetbrains-privacy-policy)
                |- JETBRAINS EAP USER AGREEMENT (https://jb.gg/jetbrains-user-eap)
                |
                |The linter includes an evaluation license.
                |The license will expire on $dateStr.
                |Please ensure you obtain a new version on time.
                """.trimMargin()
            }
            EapResult(expired = false, message = agreement)
        }
    }
}

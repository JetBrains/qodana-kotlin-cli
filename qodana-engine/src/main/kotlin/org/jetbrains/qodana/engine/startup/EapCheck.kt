package org.jetbrains.qodana.engine.startup

import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

object EapCheck {
    private const val EAP_VALIDITY_DAYS = 60L

    sealed interface EapResult {
        data class Valid(
            val expirationDate: Instant,
        ) : EapResult

        data class Expired(
            val expirationDate: Instant,
        ) : EapResult

        data class InvalidDate(
            val dateStr: String,
        ) : EapResult

        data object NotEap : EapResult
    }

    /**
     * Checks EAP license validity.
     * @param isEap whether the current build is EAP
     * @param buildDateStr RFC3339 / ISO-8601 build date string
     * @param now current time (injectable for testing)
     */
    fun checkEap(
        isEap: Boolean,
        buildDateStr: String,
        now: Instant = Instant.now(),
    ): EapResult {
        if (!isEap) return EapResult.NotEap

        val buildDate =
            try {
                Instant.parse(buildDateStr)
            } catch (_: DateTimeParseException) {
                return EapResult.InvalidDate(buildDateStr)
            }

        val deadline = buildDate.plus(EAP_VALIDITY_DAYS, ChronoUnit.DAYS)
        return if (now.isAfter(deadline)) {
            EapResult.Expired(deadline)
        } else {
            EapResult.Valid(deadline)
        }
    }
}

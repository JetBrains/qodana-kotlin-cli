package com.jetbrains.qodana.core.model

enum class ExitCode(val code: Int) {
    SUCCESS(0),
    FAIL_THRESHOLD(255),
    THRESHOLD_REACHED(2),
    EAP_EXPIRED(7),
}

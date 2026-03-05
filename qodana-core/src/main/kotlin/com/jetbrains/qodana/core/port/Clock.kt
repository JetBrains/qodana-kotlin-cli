package com.jetbrains.qodana.core.port

import java.time.Instant

fun interface Clock {
    fun now(): Instant
}

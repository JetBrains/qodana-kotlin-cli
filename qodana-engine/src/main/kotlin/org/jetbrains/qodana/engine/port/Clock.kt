package org.jetbrains.qodana.engine.port

import java.time.Instant

fun interface Clock {
    fun now(): Instant
}

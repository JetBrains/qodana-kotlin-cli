package org.jetbrains.qodana.core.model

import java.time.Instant

data class LogEvent(
    val source: LogSource,
    val stream: Stream,
    val text: String,
    val timestamp: Instant? = null,
)

enum class LogSource { CONTAINER, PROCESS, INTERNAL }
enum class Stream { STDOUT, STDERR }

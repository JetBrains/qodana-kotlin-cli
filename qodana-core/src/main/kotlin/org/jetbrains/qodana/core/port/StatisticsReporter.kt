package org.jetbrains.qodana.core.port

interface StatisticsReporter {
    suspend fun sendEvents(deviceId: String, productCode: String, events: List<Map<String, Any>>)
}

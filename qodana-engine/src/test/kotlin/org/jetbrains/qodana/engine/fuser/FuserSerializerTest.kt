package org.jetbrains.qodana.engine.fuser

import com.fasterxml.jackson.databind.ObjectMapper
import com.jetbrains.fus.reporting.model.lion3.LogEvent
import com.jetbrains.fus.reporting.model.lion3.LogEventAction
import com.jetbrains.fus.reporting.model.lion3.LogEventGroup
import com.jetbrains.fus.reporting.model.lion3.ValidatedFusRecord
import com.jetbrains.fus.reporting.model.lion3.ValidatedFusReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FuserSerializerTest {
    private val mapper = ObjectMapper()

    private fun createLogEvent(
        session: String = "test-session",
        build: String = "QD-1.0",
        bucket: String = "42",
        time: Long = 1700000000000L,
        groupId: String = "test.group",
        groupVersion: String = "1",
        recorderVersion: String = "75",
        eventId: String = "test.event",
        state: Boolean = false,
        data: MutableMap<String, Any> = mutableMapOf("key" to "value"),
    ): LogEvent =
        LogEvent(
            session,
            build,
            bucket,
            time,
            LogEventGroup(groupId, groupVersion),
            recorderVersion,
            LogEventAction(eventId, state, data = data),
        )

    private fun createReport(
        product: String = "QDKOTLIN",
        device: String = "device-123",
        recorder: String = "FUS",
        internal: Boolean = false,
        events: List<LogEvent> = listOf(createLogEvent()),
    ): ValidatedFusReport =
        ValidatedFusReport(
            product,
            device,
            recorder,
            internal,
            listOf(ValidatedFusRecord(events)),
        )

    @Test
    fun `serialize produces valid JSON with product and device`() {
        val report = createReport(product = "QDKOTLIN", device = "dev-abc")
        val json = FuserSerializer.serialize(report)

        val tree = mapper.readTree(json)
        assertEquals("QDKOTLIN", tree["product"].asText())
        assertEquals("dev-abc", tree["device"].asText())
    }

    @Test
    fun `serialize includes recorder field`() {
        val report = createReport(recorder = "FUS")
        val json = FuserSerializer.serialize(report)

        val tree = mapper.readTree(json)
        assertEquals("FUS", tree["recorder"].asText())
    }

    @Test
    fun `serialize includes events in records`() {
        val event1 = createLogEvent(eventId = "evt1")
        val event2 = createLogEvent(eventId = "evt2")
        val report = createReport(events = listOf(event1, event2))

        val json = FuserSerializer.serialize(report)
        val tree = mapper.readTree(json)

        val records = tree["records"]
        assertTrue(records.isArray)
        assertEquals(1, records.size())

        val events = records[0]["events"]
        assertTrue(events.isArray)
        assertEquals(2, events.size())
        assertEquals("evt1", events[0]["event"]["id"].asText())
        assertEquals("evt2", events[1]["event"]["id"].asText())
    }

    @Test
    fun `internal flag when true`() {
        val report = createReport(internal = true)
        val json = FuserSerializer.serialize(report)

        val tree = mapper.readTree(json)
        assertTrue(tree.has("internal"))
        assertTrue(tree["internal"].asBoolean())
    }

    @Test
    fun `internal flag when false`() {
        val report = createReport(internal = false)
        val json = FuserSerializer.serialize(report)

        val tree = mapper.readTree(json)
        assertFalse(tree.has("internal") && tree["internal"].asBoolean())
    }
}

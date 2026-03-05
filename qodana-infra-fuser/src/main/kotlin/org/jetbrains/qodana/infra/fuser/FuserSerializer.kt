package org.jetbrains.qodana.infra.fuser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.jetbrains.fus.reporting.model.lion3.LogEvent
import com.jetbrains.fus.reporting.model.lion3.ValidatedFusReport

internal object FuserSerializer {

    fun serialize(request: ValidatedFusReport): String {
        val mapper = ObjectMapper()
        val obj = mapper.createObjectNode()
        obj.put("recorder", request.recorder)
        obj.put("product", request.product)
        obj.put("device", request.device)
        if (request.internal == true) {
            obj.put("internal", true)
        }

        val records = mapper.createArrayNode()
        for (record in request.records) {
            val events = mapper.createArrayNode()
            for (event in record.events) {
                events.add(eventToJson(mapper, event))
            }
            val recordObj = mapper.createObjectNode()
            recordObj.putArray("events").addAll(events)
            records.add(recordObj)
        }

        obj.putArray("records").addAll(records)
        return obj.toString()
    }

    private fun eventToJson(mapper: ObjectMapper, event: LogEvent): ObjectNode {
        val obj = mapper.createObjectNode()
        obj.put("recorder_version", event.recorderVersion)
        obj.put("session", event.session)
        obj.put("build", event.build)
        obj.put("bucket", event.bucket)
        obj.put("time", event.time)

        val group = mapper.createObjectNode()
        group.put("id", event.group.id)
        group.put("version", event.group.version)

        val action = mapper.createObjectNode()
        if (event.event.state) {
            action.put("state", event.event.state)
        } else {
            action.put("count", event.event.count)
        }
        action.set<JsonNode>("data", mapper.valueToTree(event.event.data))
        action.put("id", event.event.id)

        obj.set<ObjectNode>("group", group)
        obj.set<ObjectNode>("event", action)
        return obj
    }
}

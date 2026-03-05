package org.jetbrains.qodana.engine.cloud

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals

class LicenseDataTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `deserialize all fields`() {
        val json = """
            {
                "licenseId": "lid-123",
                "licenseKey": "key-abc",
                "expirationDate": "2026-12-31",
                "projectIdHash": "projHash",
                "organisationIdHash": "orgHash",
                "licensePlan": "ULTIMATE"
            }
        """.trimIndent()

        val data = mapper.readValue<LicenseData>(json)

        assertEquals("lid-123", data.licenseId)
        assertEquals("key-abc", data.licenseKey)
        assertEquals("2026-12-31", data.expirationDate)
        assertEquals("projHash", data.projectIdHash)
        assertEquals("orgHash", data.organisationIdHash)
        assertEquals("ULTIMATE", data.licensePlan)
    }

    @Test
    fun `ignore unknown properties`() {
        val json = """
            {
                "licenseId": "lid-123",
                "licenseKey": "key-abc",
                "expirationDate": "2026-12-31",
                "projectIdHash": "projHash",
                "organisationIdHash": "orgHash",
                "licensePlan": "ULTIMATE",
                "unknownField": "should be ignored"
            }
        """.trimIndent()

        val data = mapper.readValue<LicenseData>(json)

        assertEquals("lid-123", data.licenseId)
    }

    @Test
    fun `extract license key`() {
        val json = """
            {
                "licenseId": "lid-456",
                "licenseKey": "extracted-key-value",
                "expirationDate": "2027-01-01",
                "projectIdHash": "ph",
                "organisationIdHash": "oh",
                "licensePlan": "COMMUNITY"
            }
        """.trimIndent()

        val data = mapper.readValue<LicenseData>(json)

        assertEquals("extracted-key-value", data.licenseKey)
    }

    @Test
    fun `license plan values`() {
        val plans = listOf("COMMUNITY", "ULTIMATE", "ULTIMATE_PLUS")

        for (plan in plans) {
            val json = """
                {
                    "licenseId": "id",
                    "licenseKey": "key",
                    "expirationDate": "2026-12-31",
                    "projectIdHash": "ph",
                    "organisationIdHash": "oh",
                    "licensePlan": "$plan"
                }
            """.trimIndent()

            val data = mapper.readValue<LicenseData>(json)

            assertEquals(plan, data.licensePlan)
        }
    }

    @Test
    fun `round trip serialization`() {
        val original = LicenseData(
            licenseId = "lid-rt",
            licenseKey = "key-rt",
            expirationDate = "2026-06-15",
            projectIdHash = "projRT",
            organisationIdHash = "orgRT",
            licensePlan = "ULTIMATE_PLUS",
        )

        val json = mapper.writeValueAsString(original)
        val deserialized = mapper.readValue<LicenseData>(json)

        assertEquals(original, deserialized)
    }
}

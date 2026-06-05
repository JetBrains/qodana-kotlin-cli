package org.jetbrains.qodana.engine.fuser

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jetbrains.fus.reporting.model.config.v4.Configuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FuserAdapterTest {
    // Synthetic config mirroring the real FUS config shape: build-gated versions whose metadata
    // endpoint differs by build. Endpoints end in "/" as the production config does. URLs are
    // synthetic on purpose — we assert format/parity, not a pinned production contract.
    private val multiVersionConfig =
        """
        {
          "productCode": "QDTEST",
          "versions": [
            {
              "majorBuildVersionBorders": { "from": "2020.1", "to": "2020.3" },
              "endpoints": {
                "metadata": "https://example.test/fus/metadata/groups/",
                "send": "https://example.test/fus/send/"
              }
            },
            {
              "majorBuildVersionBorders": { "from": "2025.1" },
              "endpoints": {
                "metadata": "https://example.test/fus/metadata/groups/251/",
                "send": "https://example.test/fus/send/"
              }
            }
          ]
        }
        """.trimIndent()

    @Test
    fun `resolveEndpoints selects the build-matching version and resolves expected URLs`() {
        val endpoints = FuserAdapter(productVersion = "2025.3").resolveEndpoints(multiVersionConfig, "QDTEST")

        // findProductVersion("2025.3") selects the from=2025.1 bucket (".../groups/251/"); a
        // versions.first() implementation would instead yield ".../groups/QDTEST.json".
        assertEquals("https://example.test/fus/metadata/groups/251/QDTEST.json", endpoints.metadataUrl)
        assertEquals("https://example.test/fus/send/", endpoints.sendUrl)
    }

    @Test
    @Suppress("DEPRECATION") // intentionally compares the deprecated old API against the new one
    fun `metadata and send URLs are unchanged after the API rename`() {
        val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        // metadata endpoint ends in "/", as every production endpoint does, so the old raw concat
        // and the new slash-aware helper resolve to the same URL.
        val json =
            """
            {"versions":[{"endpoints":{
              "metadata":"https://example.test/fus/metadata/groups/251/",
              "send":"https://example.test/fus/send/"
            }}]}
            """.trimIndent()

        val oldVersion =
            mapper
                .readValue(json, com.jetbrains.fus.reporting.model.config.v4.EventLogExternalSettings::class.java)
                .versions!!
                .first()
        val newVersion = mapper.readValue(json, Configuration::class.java).versions!!.first()

        assertEquals(oldVersion.getMetadataEndpoint("QDTEST"), newVersion.provideMetadataProductUrl("QDTEST"))
        assertEquals(oldVersion.getSendEndpoint(), newVersion.provideSendEndpoint())
        // Pin the concrete value too, so a future library change can't make both drift together unnoticed.
        assertEquals(
            "https://example.test/fus/metadata/groups/251/QDTEST.json",
            newVersion.provideMetadataProductUrl("QDTEST"),
        )
    }

    @Test
    fun `resolveEndpoints fails loudly when no config version matches the build`() {
        // A build older than every border -> findProductVersion returns its empty sentinel (no
        // endpoints), so we must fail loudly instead of fetching a bogus "null...json" URL.
        assertFailsWith<IllegalStateException> {
            FuserAdapter(productVersion = "1999.9").resolveEndpoints(multiVersionConfig, "QDTEST")
        }
    }

    @Test
    fun `resolveEndpoints tolerates unknown fields in the external config`() {
        // The remote config is external and evolves, so resolution must not break when new keys
        // appear. A strict mapper (FAIL_ON_UNKNOWN_PROPERTIES=true, the ObjectMapper default) would
        // throw UnrecognizedPropertyException here; the adapter's lenient mapper ignores them.
        val configWithUnknownFields =
            """
            {
              "productCode": "QDTEST",
              "futureTopLevelField": "ignored",
              "versions": [
                {
                  "majorBuildVersionBorders": { "from": "2025.1" },
                  "futureVersionField": { "nested": true },
                  "endpoints": {
                    "metadata": "https://example.test/fus/metadata/groups/251/",
                    "send": "https://example.test/fus/send/"
                  }
                }
              ]
            }
            """.trimIndent()

        val endpoints = FuserAdapter(productVersion = "2025.3").resolveEndpoints(configWithUnknownFields, "QDTEST")

        assertEquals("https://example.test/fus/metadata/groups/251/QDTEST.json", endpoints.metadataUrl)
        assertEquals("https://example.test/fus/send/", endpoints.sendUrl)
    }

    @Test
    fun `resolveEndpoints fails loudly when the matched version has no send endpoint`() {
        // The version matches the build and has a metadata endpoint but no send endpoint; the send
        // guard must fire rather than letting a null send URL reach the (non-null) POST.
        val configMissingSend =
            """
            {
              "versions": [
                {
                  "majorBuildVersionBorders": { "from": "2025.1" },
                  "endpoints": { "metadata": "https://example.test/fus/metadata/groups/251/" }
                }
              ]
            }
            """.trimIndent()

        assertFailsWith<IllegalStateException> {
            FuserAdapter(productVersion = "2025.3").resolveEndpoints(configMissingSend, "QDTEST")
        }
    }

    @Test
    @Suppress("DEPRECATION") // documents the new API's slash-insertion vs the old raw concat
    fun `new metadata helper inserts a separator when the endpoint lacks a trailing slash`() {
        // The single deliberate behavioral difference between old and new: for a metadata base without
        // a trailing slash the new helper inserts "/". Production endpoints all end in "/", so resolved
        // URLs are unchanged today; this pins the divergence so it can't regress unnoticed.
        val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val json =
            """
            {"versions":[{"endpoints":{
              "metadata":"https://example.test/fus/metadata/groups",
              "send":"https://example.test/fus/send/"
            }}]}
            """.trimIndent()

        val oldVersion =
            mapper
                .readValue(json, com.jetbrains.fus.reporting.model.config.v4.EventLogExternalSettings::class.java)
                .versions!!
                .first()
        val newVersion = mapper.readValue(json, Configuration::class.java).versions!!.first()

        assertEquals(
            "https://example.test/fus/metadata/groupsQDTEST.json",
            oldVersion.getMetadataEndpoint("QDTEST"),
        )
        assertEquals(
            "https://example.test/fus/metadata/groups/QDTEST.json",
            newVersion.provideMetadataProductUrl("QDTEST"),
        )
    }
}

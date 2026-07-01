package org.jetbrains.qodana.images.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManifestLoaderTest {
    // A representative manifest that exercises every field group: a multi-variant
    // run with caps/securityOpt/extraArgs, a tool expectation, and several
    // expectation flavours (present-by-ruleId, absent-by-pattern, cross-variant
    // needs-pinning, count + uri/message narrowing).
    private val sampleJson =
        """
        {
          "case": "jvm-exclude-control",
          "image": "qodana-jvm",
          "description": "exclude-by-name and path-scoped exclude remove a planted rule",
          "run": {
            "network": "none",
            "capAdd": [],
            "securityOpt": [],
            "extraArgs": ["--no-statistics"],
            "env": {},
            "failThreshold": null,
            "variants": [
              { "id": "control", "qodanaYaml": "qodana.control.yaml", "gitState": "clean" },
              { "id": "treatment", "qodanaYaml": "qodana.treatment.yaml", "gitState": "clean" }
            ]
          },
          "expectExitCode": 0,
          "sarif": {
            "tool": { "driverName": "QDJVM", "driverFullName": "Qodana for JVM" },
            "schemaValid": false,
            "qodanaSeverityRequired": true,
            "expectations": [
              {
                "ruleId": "StringEquality",
                "presence": "present",
                "count": ">=1",
                "uriContains": "planted/",
                "variant": "control",
                "pin": "confirmed",
                "guards": [],
                "reason": "control run trips StringEquality under planted/"
              },
              {
                "ruleId": "StringEquality",
                "presence": "absent",
                "variant": "treatment",
                "pin": "needs-pinning",
                "guards": ["QD-1699"],
                "reason": "exclude-by-name disables the rule in treatment"
              },
              {
                "ruleIdPattern": "^(llvmlibc|fuchsia|altera)-",
                "presence": "absent",
                "count": "==0",
                "pin": "confirmed",
                "guards": ["QD-14272"],
                "reason": "noise families must never appear"
              }
            ]
          }
        }
        """.trimIndent()

    @Test
    fun `loads a representative manifest and preserves every field`() {
        val dir = Files.createTempDirectory("manifest-test")
        val file = dir.resolve("expected.json")
        file.writeText(sampleJson)

        val m = ManifestLoader.load(file)

        assertEquals("jvm-exclude-control", m.case)
        assertEquals("qodana-jvm", m.image)
        assertEquals("none", m.run.network)
        assertEquals(listOf("--no-statistics"), m.run.extraArgs)
        assertEquals(null, m.run.failThreshold)
        assertEquals(2, m.run.variants.size)
        assertEquals("control", m.run.variants[0].id)
        assertEquals("qodana.control.yaml", m.run.variants[0].qodanaYaml)
        assertEquals("clean", m.run.variants[0].gitState)
        assertEquals(0, m.expectExitCode)
        assertEquals("QDJVM", m.sarif.tool?.driverName)
        assertEquals("Qodana for JVM", m.sarif.tool?.driverFullName)
        assertTrue(m.sarif.qodanaSeverityRequired)
        assertEquals(3, m.sarif.expectations.size)

        val present = m.sarif.expectations[0]
        assertEquals("StringEquality", present.ruleId)
        assertEquals("present", present.presence)
        assertEquals(">=1", present.count)
        assertEquals("planted/", present.uriContains)
        assertEquals("control", present.variant)
        assertEquals("confirmed", present.pin)

        val pinned = m.sarif.expectations[1]
        assertEquals("absent", pinned.presence)
        assertEquals("needs-pinning", pinned.pin)
        assertEquals(listOf("QD-1699"), pinned.guards)

        val family = m.sarif.expectations[2]
        assertEquals("^(llvmlibc|fuchsia|altera)-", family.ruleIdPattern)
        assertEquals("==0", family.count)
    }

    @Test
    fun `defaults apply for a minimal manifest`() {
        val dir = Files.createTempDirectory("manifest-test")
        val file = dir.resolve("expected.json")
        file.writeText(
            """{"case":"c","image":"qodana-jvm","description":"d"}""",
        )

        val m = ManifestLoader.load(file)

        assertEquals("none", m.run.network)
        assertEquals(emptyList(), m.run.capAdd)
        assertEquals(0, m.expectExitCode)
        assertEquals(1, m.run.variants.size)
        assertEquals(
            "default",
            m.run.variants
                .single()
                .id,
        )
        assertEquals(
            "clean",
            m.run.variants
                .single()
                .gitState,
        )
        assertEquals(false, m.sarif.qodanaSeverityRequired)
        assertEquals(emptyList(), m.sarif.expectations)
    }

    @Test
    fun `rejects a manifest that declares a log field`() {
        val dir = Files.createTempDirectory("manifest-test")
        val file = dir.resolve("expected.json")
        // e2e asserts the wrapper contract, not engine log cleanliness.
        file.writeText("""{"case":"c","image":"qodana-jvm","description":"d","log":{"mustNotContain":[]}}""")
        assertFailsWith<com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException> {
            ManifestLoader.load(file)
        }
    }

    // QD-15022 validity floor: the committed schema file must be syntactically
    // valid JSON. Full JSON-Schema *validation* of every expected.json against
    // it is deferred (needs a validator dep); for now we (a) parse the schema,
    // (b) round-trip the model, which is the deserialize-cleanly floor.
    @Test
    fun `committed expected-manifest schema is valid JSON`() {
        val schema = Path.of("e2e/expected-manifest.schema.json")
        assertTrue(Files.exists(schema), "expected schema at $schema (cwd is the linter-images module root)")
        val tree = ObjectMapper().readTree(Files.readString(schema))
        assertEquals("object", tree.get("type").asText(), "schema root must describe an object")
        assertTrue(tree.has("properties"), "schema must declare top-level properties")
    }
}

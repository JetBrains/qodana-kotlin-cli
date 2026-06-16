package org.jetbrains.qodana.images.e2e

import com.jetbrains.qodana.sarif.model.ArtifactLocation
import com.jetbrains.qodana.sarif.model.Location
import com.jetbrains.qodana.sarif.model.Message
import com.jetbrains.qodana.sarif.model.PhysicalLocation
import com.jetbrains.qodana.sarif.model.PropertyBag
import com.jetbrains.qodana.sarif.model.Result
import com.jetbrains.qodana.sarif.model.Run
import com.jetbrains.qodana.sarif.model.SarifReport
import com.jetbrains.qodana.sarif.model.Tool
import com.jetbrains.qodana.sarif.model.ToolComponent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SarifExpectationEvaluatorTest {
    private val evaluator = SarifExpectationEvaluator()

    // ----- canned SARIF builders -----------------------------------------
    // The qodana-sarif model is a plain POJO tree with chaining `with*`
    // setters. A single-run report is: SarifReport(runs=[Run(tool=Tool(
    // driver=ToolComponent(name)), results=[Result...])]).

    private fun result(
        ruleId: String? = null,
        uri: String? = null,
        message: String? = null,
        qodanaSeverity: String? = null,
    ): Result {
        val r = Result(Message().withText(message ?: "msg"))
        if (ruleId != null) r.withRuleId(ruleId)
        if (uri != null) {
            r.withLocations(
                listOf(
                    Location().withPhysicalLocation(
                        PhysicalLocation().withArtifactLocation(
                            ArtifactLocation().withUri(uri),
                        ),
                    ),
                ),
            )
        }
        if (qodanaSeverity != null) {
            val props = PropertyBag()
            props["qodanaSeverity"] = qodanaSeverity
            r.withProperties(props)
        }
        return r
    }

    private fun report(
        results: List<Result>,
        driverName: String = "QDJVM",
        driverFullName: String? = "Qodana for JVM",
    ): SarifReport {
        val driver = ToolComponent(driverName)
        if (driverFullName != null) driver.fullName = driverFullName
        val run = Run(Tool(driver)).withResults(results)
        return SarifReport(SarifReport.Version._2_1_0, listOf(run))
    }

    private fun expectation(
        ruleId: String? = null,
        ruleIdPattern: String? = null,
        presence: String,
        count: String? = null,
        uriContains: String? = null,
        messageContains: String? = null,
    ) = Expectation(
        ruleId = ruleId,
        ruleIdPattern = ruleIdPattern,
        presence = presence,
        count = count,
        uriContains = uriContains,
        messageContains = messageContains,
        reason = "test",
    )

    private fun sarif(vararg expectations: Expectation) = SarifExpectations(expectations = expectations.toList())

    // ----- presence by ruleId --------------------------------------------

    @Test
    fun `present by ruleId holds`() {
        val rep = report(listOf(result("StringEquality")))
        val v = evaluator.evaluate(rep, sarif(expectation(ruleId = "StringEquality", presence = "present")))
        assertEquals(emptyList(), v)
    }

    @Test
    fun `present by ruleId is violated when absent`() {
        val rep = report(listOf(result("Other")))
        val v = evaluator.evaluate(rep, sarif(expectation(ruleId = "StringEquality", presence = "present")))
        assertEquals(1, v.size)
    }

    @Test
    fun `absent by ruleId holds when no such result`() {
        val rep = report(listOf(result("Other")))
        val v = evaluator.evaluate(rep, sarif(expectation(ruleId = "StringEquality", presence = "absent")))
        assertEquals(emptyList(), v)
    }

    @Test
    fun `absent by ruleId is violated when present`() {
        val rep = report(listOf(result("StringEquality")))
        val v = evaluator.evaluate(rep, sarif(expectation(ruleId = "StringEquality", presence = "absent")))
        assertEquals(1, v.size)
    }

    // ----- ruleIdPattern (regex, whole-family) ----------------------------

    @Test
    fun `ruleIdPattern family absent holds when no member present`() {
        val rep = report(listOf(result("bugprone-use-after-move"), result("clang-analyzer-core.NullDereference")))
        val v =
            evaluator.evaluate(
                rep,
                sarif(expectation(ruleIdPattern = "^(llvmlibc|fuchsia|altera)-", presence = "absent")),
            )
        assertEquals(emptyList(), v)
    }

    @Test
    fun `ruleIdPattern family absent is violated when a member is present`() {
        val rep = report(listOf(result("fuchsia-default-arguments-calls")))
        val v =
            evaluator.evaluate(
                rep,
                sarif(expectation(ruleIdPattern = "^(llvmlibc|fuchsia|altera)-", presence = "absent")),
            )
        assertEquals(1, v.size)
    }

    // ----- count ----------------------------------------------------------

    @Test
    fun `count exactly N holds`() {
        val rep = report(listOf(result("DuplicatedCode"), result("DuplicatedCode")))
        val v =
            evaluator.evaluate(
                rep,
                sarif(expectation(ruleId = "DuplicatedCode", presence = "present", count = "==2")),
            )
        assertEquals(emptyList(), v)
    }

    @Test
    fun `count exactly N is violated by doubling`() {
        val rep =
            report(
                listOf(
                    result("DuplicatedCode"),
                    result("DuplicatedCode"),
                    result("DuplicatedCode"),
                    result("DuplicatedCode"),
                ),
            )
        val v =
            evaluator.evaluate(
                rep,
                sarif(expectation(ruleId = "DuplicatedCode", presence = "present", count = "==2")),
            )
        assertEquals(1, v.size)
    }

    @Test
    fun `count at least one holds`() {
        val rep = report(listOf(result("StringEquality")))
        val v =
            evaluator.evaluate(
                rep,
                sarif(expectation(ruleId = "StringEquality", presence = "present", count = ">=1")),
            )
        assertEquals(emptyList(), v)
    }

    @Test
    fun `count equals zero holds when family absent`() {
        val rep = report(listOf(result("bugprone-x")))
        val v =
            evaluator.evaluate(
                rep,
                sarif(expectation(ruleIdPattern = "^(llvmlibc|fuchsia|altera)-", presence = "absent", count = "==0")),
            )
        assertEquals(emptyList(), v)
    }

    // ----- uriContains scoping --------------------------------------------

    @Test
    fun `uriContains scopes counted results`() {
        val rep =
            report(
                listOf(
                    result("StringEquality", uri = "planted/Main.java"),
                    result("StringEquality", uri = "excluded/Other.java"),
                ),
            )
        // exactly one of the two StringEquality results is under planted/ (count==1
        // is load-bearing: it fails if uriContains were ignored and both counted) ...
        val present =
            evaluator.evaluate(
                rep,
                sarif(
                    expectation(
                        ruleId = "StringEquality",
                        presence = "present",
                        uriContains = "planted/",
                        count = "==1",
                    ),
                ),
            )
        assertEquals(emptyList(), present)
        // ... but absent under cleaned/ (no such uri)
        val absent =
            evaluator.evaluate(
                rep,
                sarif(expectation(ruleId = "StringEquality", presence = "absent", uriContains = "cleaned/")),
            )
        assertEquals(emptyList(), absent)
    }

    @Test
    fun `uri-only scope guard counts a null-ruleId result`() {
        // QD-9251: a uri-only expectation (no ruleId/ruleIdPattern) must count a finding
        // whose ruleId is null. Guards the matchesRule "else -> true" path.
        val rep = report(listOf(result(ruleId = null, uri = "third_party/vendor.c")))
        val present =
            evaluator.evaluate(
                rep,
                sarif(expectation(presence = "present", uriContains = "third_party/", count = "==1")),
            )
        assertEquals(emptyList(), present)
        val absent =
            evaluator.evaluate(
                rep,
                sarif(expectation(presence = "absent", uriContains = "third_party/")),
            )
        assertEquals(1, absent.size)
    }

    @Test
    fun `explicit ruleId selector does not match a null-ruleId result`() {
        // The flip side of the scope guard: a null-ruleId finding must NOT satisfy an
        // expectation that names a concrete ruleId.
        val rep = report(listOf(result(ruleId = null, uri = "third_party/vendor.c")))
        val v = evaluator.evaluate(rep, sarif(expectation(ruleId = "StringEquality", presence = "present")))
        assertEquals(1, v.size)
    }

    // ----- messageContains ------------------------------------------------

    @Test
    fun `messageContains narrows matching results`() {
        val rep =
            report(
                listOf(
                    result("R", message = "unknown target triple"),
                    result("R", message = "ordinary message"),
                ),
            )
        val v =
            evaluator.evaluate(
                rep,
                sarif(
                    expectation(
                        ruleId = "R",
                        presence = "present",
                        messageContains = "unknown target triple",
                        count = "==1",
                    ),
                ),
            )
        assertEquals(emptyList(), v)
    }

    @Test
    fun `messageContains does not match a result with no message text`() {
        // A Result whose Message carries no text must not satisfy a messageContains
        // expectation (guards the matchesMessage null-text branch).
        val rep = report(listOf(Result(Message()).withRuleId("R")))
        val v =
            evaluator.evaluate(
                rep,
                sarif(expectation(ruleId = "R", presence = "present", messageContains = "anything")),
            )
        assertEquals(1, v.size)
    }

    // ----- tool driver equality -------------------------------------------

    @Test
    fun `tool driverName mismatch is a violation`() {
        val rep = report(listOf(result("R")), driverName = "QDANDC")
        val v = evaluator.evaluate(rep, SarifExpectations(tool = ToolExpectation(driverName = "QDJVM")))
        assertEquals(1, v.size)
    }

    @Test
    fun `tool driverName and fullName match holds`() {
        val rep = report(listOf(result("R")), driverName = "QDJVM", driverFullName = "Qodana for JVM")
        val v =
            evaluator.evaluate(
                rep,
                SarifExpectations(tool = ToolExpectation(driverName = "QDJVM", driverFullName = "Qodana for JVM")),
            )
        assertEquals(emptyList(), v)
    }

    @Test
    fun `tool driverFullName mismatch is a violation`() {
        val rep = report(listOf(result("R")), driverFullName = "Qodana Community for JVM")
        val v = evaluator.evaluate(rep, SarifExpectations(tool = ToolExpectation(driverFullName = "Qodana for JVM")))
        assertEquals(1, v.size)
    }

    // ----- qodanaSeverityRequired -----------------------------------------

    @Test
    fun `qodanaSeverityRequired holds when every result carries a valid severity`() {
        val rep = report(listOf(result("R", qodanaSeverity = "High"), result("S", qodanaSeverity = "Moderate")))
        val v = evaluator.evaluate(rep, SarifExpectations(qodanaSeverityRequired = true))
        assertEquals(emptyList(), v)
    }

    @Test
    fun `qodanaSeverityRequired is violated when a result lacks severity`() {
        val rep = report(listOf(result("R", qodanaSeverity = "High"), result("S")))
        val v = evaluator.evaluate(rep, SarifExpectations(qodanaSeverityRequired = true))
        assertEquals(1, v.size)
    }

    @Test
    fun `qodanaSeverityRequired is violated by an out-of-vocabulary severity`() {
        val rep = report(listOf(result("R", qodanaSeverity = "Blocker")))
        val v = evaluator.evaluate(rep, SarifExpectations(qodanaSeverityRequired = true))
        assertEquals(1, v.size)
    }

    // ----- multiple expectations aggregate -------------------------------

    @Test
    fun `violations accumulate across expectations`() {
        val rep = report(listOf(result("Present")))
        val v =
            evaluator.evaluate(
                rep,
                sarif(
                    expectation(ruleId = "Missing", presence = "present"),
                    expectation(ruleId = "Present", presence = "absent"),
                ),
            )
        assertEquals(2, v.size)
        assertTrue(v.all { it.reason == "test" })
    }
}

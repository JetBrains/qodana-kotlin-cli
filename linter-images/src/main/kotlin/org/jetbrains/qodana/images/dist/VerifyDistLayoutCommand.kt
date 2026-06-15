package org.jetbrains.qodana.images.dist

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path

/**
 * Asserts the provisioned IDE distribution at `--dist` is the expected product and carries a bundled
 * JBR runtime (product code + `jbr/bin/java` + `jbr/release`). It does NOT require a complete JDK —
 * the bundled JBR has no `jar`/`jdk.jartool` by design (QD-14924's complete JDK is a scan-time
 * bootstrap concern). Run in the builder stage BEFORE `COPY --from=builder` so a broken dist fails
 * the build instead of shipping. `--expected-product-code` is the IDE `product-info.json` code (e.g.
 * `IU`), supplied by the per-linter `.env` as `QD_PRODUCT_INFO_CODE`.
 */
class VerifyDistLayoutCommand(
    private val verifier: DistLayoutVerifier = DistLayoutVerifier(),
) : CliktCommand(name = "verify-dist-layout") {
    override fun help(context: Context) = "Verify the dist layout: product code + bundled JBR runtime"

    private val dist by option("--dist", help = "Provisioned distribution directory")
        .path(mustExist = true, canBeFile = false)
        .required()
    private val expectedProductCode by option(
        "--expected-product-code",
        help = "IDE product-info.json code the dist must declare (e.g. IU)",
    ).required()

    override fun run() {
        verifier.verify(dist, expectedProductCode)
    }
}

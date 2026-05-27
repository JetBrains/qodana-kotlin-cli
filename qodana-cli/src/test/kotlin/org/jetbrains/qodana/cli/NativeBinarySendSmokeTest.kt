package org.jetbrains.qodana.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end smoke test for the `send` command **as executed by the native
 * binary**, not the JVM.  Hosts a tiny in-process HTTP server on the JVM
 * side that impersonates both the Qodana Cloud control plane and the S3
 * upload endpoint, then forks `qodana-cli send` as a subprocess pointing at
 * the local server via `QODANA_ENDPOINT`.
 *
 * Used by the CI `native-e2e` job (.github/workflows/ci.yaml) on every
 * platform to verify the native binary can complete the full send flow —
 * which exercises every QDCloudClient and Publisher reflection path. If a
 * reflect-config entry is missing in the committed metadata, the subprocess
 * fails fast with `MissingReflectionRegistrationError` and the test reports
 * the captured stdout/stderr.
 *
 * Inputs via `-D` system properties so CI can pass platform-specific paths:
 *
 *   -Dtest.qodana.binary    Absolute path to the native binary
 *   -Dtest.project.dir      Directory the `-i` flag should point at
 *   -Dtest.results.dir      Directory the `-o` flag should point at; must
 *                           already contain a `qodana.sarif.json`
 *
 * Tagged `native-binary` (not `docker`) so the default `test` task skips it
 * — the native binary itself is the dependency, fed by CI from the
 * `native-build` job's artifact.
 */
@Tag("native-binary")
class NativeBinarySendSmokeTest {
    @Test
    fun `native binary send command exits 0 against the local mock cloud`(
        @TempDir dir: Path,
    ) {
        val binary = resolveBinary()
        val projectDir =
            System.getProperty("test.project.dir")?.let(Path::of)
                ?: Files.createDirectories(dir.resolve("project"))
        val resultsDir =
            System.getProperty("test.results.dir")?.let(Path::of)
                ?: Files.createDirectories(dir.resolve("results"))
        ensureSarif(resultsDir)

        // Stand up two servers on free ports bound to 127.0.0.1 (not 0.0.0.0)
        // so IPv4/IPv6 hostname-resolution flakiness can't bite the subprocess.
        val cloudServer = newServer()
        val s3Server = newServer()
        val cloudUrl = "http://127.0.0.1:${cloudServer.address.port}"
        val s3Url = "http://127.0.0.1:${s3Server.address.port}/upload/sarif"
        wireS3(s3Server)
        wireCloud(cloudServer, cloudUrl = cloudUrl, s3Url = s3Url)
        cloudServer.start()
        s3Server.start()

        try {
            val (exit, output) =
                runNativeSend(
                    binary = binary,
                    projectDir = projectDir,
                    resultsDir = resultsDir,
                    cloudUrl = cloudUrl,
                )
            assertEquals(0, exit, "native binary `send` exited $exit; output:\n$output")
            assertTrue(
                output.contains("Report published:"),
                "expected 'Report published:' in subprocess output; got:\n$output",
            )
        } finally {
            cloudServer.stop(0)
            s3Server.stop(0)
        }
    }

    private fun resolveBinary(): Path {
        val binary =
            Path.of(
                System.getProperty("test.qodana.binary")
                    ?: error("set -Dtest.qodana.binary=/abs/path/to/qodana-cli"),
            )
        check(Files.exists(binary)) { "native binary not found: $binary" }
        return binary
    }

    private fun ensureSarif(resultsDir: Path) {
        // The fixture in CI already provides a qodana.sarif.json from the
        // earlier scan step; locally we write a minimal stub.
        val sarif = resultsDir.resolve("qodana.sarif.json")
        if (!Files.exists(sarif)) {
            sarif.writeText(
                """{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"test"}},"results":[]}]}""",
            )
        }
    }

    private fun wireS3(s3Server: HttpServer) {
        // S3: accept the PUT, respond 200.
        s3Server.createContext("/upload/sarif") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
    }

    private fun wireCloud(
        cloudServer: HttpServer,
        cloudUrl: String,
        s3Url: String,
    ) {
        // Cloud: dispatch on the path.  These are the endpoints the binary
        // exercises during `send`, plus a default handler to surface unexpected
        // requests in test output instead of silently 404'ing.
        cloudServer.createContext("/api/versions") { ex ->
            // CloudClient.fetchEndpoints + QDCloudByFrontendEnvironment both
            // hit /api/versions; both expect the same BackendUrls shape.
            ex.respondJson(
                200,
                """{"api":{"versions":[{"version":"1.1","url":"$cloudUrl"}]},""" +
                    """"linters":{"versions":[{"version":"1.0","url":"$cloudUrl"}]}}""",
            )
        }
        cloudServer.createContext("/projects") { ex ->
            // SendCommand validates the project token by hitting /projects.
            ex.respondJson(
                200,
                """{"id":"proj1","organizationId":"org1","name":"sample-project"}""",
            )
        }
        cloudServer.createContext("/") { ex ->
            // Publisher endpoints live directly under the cloud root (no
            // /v1/projects/{token}/ prefix). Empirically the native binary
            // POSTs to "/reports/" and "/reports/{id}/finish/" — observed via
            // a tcpdump-style HTTP mock on darwin-arm64. Anything else here
            // surfaces the unhandled path so failures are easy to localise.
            handlePublisherOrDefault(ex, cloudUrl = cloudUrl, s3Url = s3Url)
        }
    }

    private fun handlePublisherOrDefault(
        ex: HttpExchange,
        cloudUrl: String,
        s3Url: String,
    ) {
        val path = ex.requestURI.path
        when {
            path.endsWith("/reports/") && ex.requestMethod == "POST" -> {
                ex.respondJson(
                    200,
                    """{"reportId":"r1","fileLinks":{"qodana.sarif.json":"$s3Url"},"langsRequired":false}""",
                )
            }
            path.endsWith("/finish/") -> {
                ex.respondJson(
                    200,
                    """{"token":"finished-token","url":"$cloudUrl/reports/r1"}""",
                )
            }
            else -> ex.respondJson(404, """{"error":"unhandled path: $path"}""")
        }
    }

    private fun runNativeSend(
        binary: Path,
        projectDir: Path,
        resultsDir: Path,
        cloudUrl: String,
    ): Pair<Int, String> {
        val proc =
            ProcessBuilder(
                binary.toString(),
                "send",
                "-i",
                projectDir.toString(),
                "-o",
                resultsDir.toString(),
            ).apply {
                environment()["QODANA_TOKEN"] = "stub-token"
                environment()["QODANA_LICENSE_ONLY_TOKEN"] = "stub-token"
                environment()["QODANA_ENDPOINT"] = cloudUrl
                redirectErrorStream(true)
            }.start()

        val finished = proc.waitFor(2, TimeUnit.MINUTES)
        val output = proc.inputStream.bufferedReader().readText()
        if (!finished) {
            proc.destroyForcibly()
            fail("native send subprocess timed out after 2 minutes; output:\n$output")
        }
        return proc.exitValue() to output
    }

    private fun newServer(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    private fun HttpExchange.respondJson(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

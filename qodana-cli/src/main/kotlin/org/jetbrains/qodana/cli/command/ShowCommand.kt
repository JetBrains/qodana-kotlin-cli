package org.jetbrains.qodana.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jetbrains.qodana.engine.env.CiDetector
import org.jetbrains.qodana.engine.cloud.getReportUrl
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.extension
import kotlin.io.path.readBytes

class ShowCommand(
    private val terminal: Terminal,
) : CliktCommand("show") {

    override fun help(context: Context) = "Show a Qodana report"

    private val linter by option("-l", "--linter", help = "Override linter to use")
    private val projectDir by option("-i", "--project-dir", help = "Root directory of the project")
        .path(mustExist = true)
        .default(Path.of("."))
    private val resultsDir by option("-o", "--results-dir", help = "Override results directory").path()
    private val reportDir by option("-r", "--report-dir", help = "Override report directory").path()
    private val port by option("-p", "--port", help = "Port to serve report at").int().default(8080)
    private val openDir by option("-d", "--dir-only", help = "Open report directory only").flag()
    private val configName by option("--config", help = "Custom configuration file")

    override fun run() {
        val absProjectDir = projectDir.toAbsolutePath().normalize()
        val yaml = CliPathResolver.loadYaml(absProjectDir, configName)
        val resolvedLinter = CliPathResolver.resolveLinterName(linter, yaml, absProjectDir)
        val resolvedPaths = CliPathResolver.resolvePaths(
            projectDir = absProjectDir,
            linterName = resolvedLinter,
            resultsDir = resultsDir,
            cacheDir = null,
            reportDir = reportDir,
            isContainer = CiDetector.isContainer(),
        )
        val dir = resolvedPaths.reportDir

        if (openDir) {
            val targetDir = resolvedPaths.resultsDir
            if (!Files.isDirectory(targetDir)) {
                terminal.error("Results directory not found: $targetDir")
                throw ProgramResult(1)
            }
            terminal.println("Opening results directory: $targetDir")
            openDirectory(targetDir)
            return
        }

        val cloudUrl = getReportUrl(resolvedPaths.resultsDir.toString())
        if (cloudUrl.isNotBlank()) {
            terminal.println("Opening Qodana cloud report: $cloudUrl")
            if (openUrl(cloudUrl)) {
                return
            }
            terminal.warn("Could not open cloud report URL, falling back to local report")
        }

        val indexHtml = dir.resolve("index.html")
        if (!Files.exists(indexHtml)) {
            terminal.error("No report found at $indexHtml. Run 'qodana scan' first.")
            throw ProgramResult(1)
        }

        serveReport(indexHtml.parent, port)
    }

    private fun openDirectory(path: Path) {
        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("mac") -> arrayOf("open", path.toString())
                os.contains("linux") -> arrayOf("xdg-open", path.toString())
                os.contains("windows") -> arrayOf("cmd", "/c", "start", path.toString())
                else -> {
                    terminal.println("Path: $path")
                    return
                }
            }
            Runtime.getRuntime().exec(cmd)
        } catch (_: Exception) {
            terminal.println("Could not open. Path: $path")
        }
    }

    private fun serveReport(reportDir: Path, port: Int) {
        val server = HttpServer.create(java.net.InetSocketAddress(port), 0)
        server.createContext("/") { exchange ->
            handleStaticRequest(exchange, reportDir)
        }
        server.start()
        terminal.println("Showing Qodana report from http://localhost:$port")
        openUrl("http://localhost:$port")

        Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
        CountDownLatch(1).await()
    }

    private fun handleStaticRequest(exchange: HttpExchange, reportDir: Path) {
        val rawPath = exchange.requestURI.path.removePrefix("/")
        val requested = if (rawPath.isBlank()) reportDir.resolve("index.html") else reportDir.resolve(rawPath)
        val normalized = requested.normalize()

        if (!normalized.startsWith(reportDir) || !Files.exists(normalized) || Files.isDirectory(normalized)) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }

        val body = normalized.readBytes()
        exchange.responseHeaders.add("Cache-Control", "no-cache, private, max-age=0")
        exchange.responseHeaders.add("Pragma", "no-cache")
        exchange.responseHeaders.add("Expires", "0")
        exchange.responseHeaders.add("Content-Type", contentType(normalized))
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { out -> out.write(body) }
    }

    private fun openUrl(url: String): Boolean {
        return try {
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("mac") -> arrayOf("open", url)
                os.contains("linux") -> arrayOf("xdg-open", url)
                os.contains("windows") -> arrayOf("cmd", "/c", "start", url)
                else -> return false
            }
            Runtime.getRuntime().exec(cmd)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun contentType(path: Path): String {
        return when (path.extension.lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}

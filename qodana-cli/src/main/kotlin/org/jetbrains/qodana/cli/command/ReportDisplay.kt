package org.jetbrains.qodana.cli.command

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jetbrains.qodana.engine.cloud.getReportUrl
import org.jetbrains.qodana.core.port.Terminal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.extension
import kotlin.io.path.readBytes

object ReportDisplay {
    fun showReport(terminal: Terminal, resultsDir: Path, reportDir: Path, port: Int): Int {
        val cloudUrl = getReportUrl(resultsDir.toString())
        if (cloudUrl.isNotBlank()) {
            terminal.println("Opening Qodana cloud report: $cloudUrl")
            if (openUrl(cloudUrl)) {
                return 0
            }
            terminal.warn("Could not open cloud report URL, falling back to local report")
        }

        val indexHtml = reportDir.resolve("index.html")
        if (!Files.exists(indexHtml)) {
            terminal.error("No report found at $indexHtml. Run 'qodana scan' first.")
            return 1
        }

        serveReport(terminal, indexHtml.parent, port)
        return 0
    }

    private fun serveReport(terminal: Terminal, reportDir: Path, port: Int) {
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

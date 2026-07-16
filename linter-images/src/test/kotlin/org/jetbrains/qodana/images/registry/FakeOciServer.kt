package org.jetbrains.qodana.images.registry

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64

/** In-process OCI Distribution v2 fake for wire-level `SpaceRegistryClient` tests; no real network.
 * Register only the endpoints a test needs via [on]. */
class FakeOciServer(
    val username: String = "bot",
    val password: String = "s3cr3t",
    private val bearerToken: String = "faketoken",
) {
    private val server = HttpServer.create(InetSocketAddress(0), 0)
    val port: Int get() = server.address.port
    val host: String get() = "localhost:$port"
    val tokenRequests = mutableListOf<String>()

    /** When > 0, every AUTHENTICATED `/v2/...` request gets a 401 challenge regardless of its bearer,
     * decrementing by one — simulates the server rejecting a token the client still considers valid/cached.
     * The unauthenticated base `/v2/` discovery ping (no bearer) does NOT consume a rejection. */
    var rejectNextAuthCount = 0
    private val handlers = mutableMapOf<String, HttpHandler>()

    init {
        server.createContext("/") { exchange -> route(exchange) }
        server.start()
    }

    fun stop() = server.stop(0)

    fun on(
        method: String,
        path: String,
        handler: HttpHandler,
    ) {
        handlers["$method $path"] = handler
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        when {
            path == "/oauth/token" -> handleToken(exchange)
            path.startsWith("/v2/") -> routeV2(exchange, path)
            else -> dispatch(exchange, path)
        }
    }

    private fun routeV2(
        exchange: HttpExchange,
        path: String,
    ) {
        val bearer = exchange.requestHeaders.getFirst("Authorization")
        // Reject only ALREADY-authenticated requests — this simulates the server rejecting a token the
        // client still holds. Gating on the bearer keeps the unauthenticated base `/v2/` discovery ping
        // (which carries no Authorization header) from consuming a rejection.
        if (bearer?.startsWith("Bearer ") == true && rejectNextAuthCount > 0) {
            rejectNextAuthCount--
            challenge(exchange)
            return
        }
        if (bearer != "Bearer $bearerToken") {
            challenge(exchange)
            return
        }
        dispatch(exchange, path)
    }

    private fun challenge(exchange: HttpExchange) {
        exchange.responseHeaders.add(
            "WWW-Authenticate",
            """Bearer realm="http://$host/oauth/token", service="test-registry"""",
        )
        exchange.sendResponseHeaders(401, -1)
        exchange.close()
    }

    private fun dispatch(
        exchange: HttpExchange,
        path: String,
    ) {
        val handler = handlers["${exchange.requestMethod} $path"]
        if (handler == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        } else {
            handler.handle(exchange)
        }
    }

    private fun handleToken(exchange: HttpExchange) {
        tokenRequests += exchange.requestURI.query ?: ""
        val expected = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        if (exchange.requestHeaders.getFirst("Authorization") != expected) {
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }
        respond(exchange, 200, """{"access_token":"$bearerToken"}""", "application/json")
    }

    companion object {
        fun respond(
            exchange: HttpExchange,
            status: Int,
            body: String,
            contentType: String,
        ) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}

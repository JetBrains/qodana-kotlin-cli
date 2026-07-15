package org.jetbrains.qodana.images.registry

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.jetbrains.qodana.images.RegistryTag
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.time.Instant
import java.util.Base64

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TagsListResponse(
    @JsonProperty("tags") val tags: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ManifestPlatform(
    @JsonProperty("architecture") val architecture: String = "",
    @JsonProperty("os") val os: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ManifestDescriptor(
    @JsonProperty("digest") val digest: String,
    @JsonProperty("platform") val platform: ManifestPlatform? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ImageIndex(
    @JsonProperty("manifests") val manifests: List<ManifestDescriptor> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ImageManifest(
    @JsonProperty("config") val config: ManifestDescriptor,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ImageConfig(
    @JsonProperty("created") val created: String,
)

private const val ACCEPT_INDEX = "application/vnd.oci.image.index.v1+json"
private const val ACCEPT_MANIFEST = "application/vnd.oci.image.manifest.v1+json"

// The Docker Registry token spec defines an absent `expires_in` as 60s; Space omits the field. This is a
// client-side cache margin, not a trust boundary — `withAuth` re-authenticates and retries once on any
// 401 regardless of what this cache believes, so a wrong guess here costs one retry, not a wrong result.
private const val TOKEN_CACHE_SECONDS = 45L

// Failure bound surfaced to the operator for a remote call that could otherwise hang on a half-open
// connection (the registry or the redirected CloudFront blob host). Well under any CI job timeout.
private val CONNECT_TIMEOUT = Duration.ofSeconds(30)
private val REQUEST_TIMEOUT = Duration.ofSeconds(60)

private data class CachedToken(val value: String, val expiresAt: Instant)

/**
 * HTTP adapter over the Space OCI Distribution v2 API. Auth: the realm/service pair in the 401 challenge
 * is a registry-host constant, discovered once (via the base `/v2/` ping endpoint) and cached; bearer
 * tokens are cached per (repo, actions) for `TOKEN_CACHE_SECONDS`, and any 401 evicts the cache and
 * retries once with a freshly-authenticated token. `pushed` isn't in the OCI tags list — it's resolved via
 * the tag's linux/amd64 child manifest's config blob `created` field (that blob read redirects to a signed
 * CDN URL on a different host, hence `Redirect.NORMAL`); a tag whose manifest chain can't be resolved this
 * way falls back to `Instant.MAX` with a warning, rather than aborting the whole repo. `repo` is
 * `<host>/<path...>`; this client splits the host off internally, and `scheme` is pluggable so tests can
 * point it at a plain-HTTP local fixture.
 */
class SpaceRegistryClient(
    private val username: String,
    private val password: String,
    private val scheme: String = "https",
    private val http: HttpClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT_TIMEOUT).build(),
) : RegistryClient {
    private val mapper = ObjectMapper().registerModule(kotlinModule())
    private val challengeCache = mutableMapOf<String, Pair<String, String>>() // host -> (realm, service)
    private val tokenCache = mutableMapOf<Pair<String, String>, CachedToken>() // (repo, actions) -> token
    // (repo, tag) -> index digest. Purpose: skip a redundant re-fetch within ONE listing. Cleared per-repo
    // at the start of each listTags so a re-verify listing never serves a digest learned in a prior pass.
    private val digestCache = mutableMapOf<Pair<String, String>, String>()

    override fun listTags(repo: String): List<RegistryTag> {
        digestCache.keys.removeAll { it.first == repo }
        val names = mutableListOf<String>()
        // Cycle guard by CANONICAL page identity (path + sorted, decoded query params), so a repeat page
        // that only differs cosmetically (param order, percent-encoding) is still caught — a syntactic
        // URI compare would miss it. No page cap: the guarantee is progress, not an arbitrary bound.
        val visited = mutableSetOf<String>()
        var next: URI? = URI("${apiBase(repo)}/tags/list")
        while (next != null) {
            val uri = next
            check(visited.add(canonicalKey(uri))) { "tags/list pagination did not advance (repeated page) for $repo: $uri" }
            val resp = get(repo, uri, "pull")
            check(resp.statusCode() == 200) { "tags/list failed for $repo: ${resp.statusCode()} ${resp.body()}" }
            names += mapper.readValue<TagsListResponse>(resp.body()).tags
            next = resp.headers().firstValue("Link").orElse(null)?.let { nextLink(it, uri) }
        }
        return names.map { name ->
            val pushed =
                try {
                    pushedAt(repo, name)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: Exception) {
                    System.err.println(
                        "warning: could not resolve push time for $repo:$name (${e.message}); " +
                            "treating as never-prunable-by-age",
                    )
                    Instant.MAX
                }
            RegistryTag(name, pushed)
        }
    }

    override fun resolveDigest(
        repo: String,
        tag: String,
    ): String? {
        digestCache[repo to tag]?.let { return it }
        val resp = get(repo, URI("${apiBase(repo)}/manifests/$tag"), "pull", accept = ACCEPT_INDEX)
        if (resp.statusCode() == 404) return null
        check(resp.statusCode() == 200) { "manifest fetch failed for $repo:$tag: ${resp.statusCode()} ${resp.body()}" }
        val digest =
            resp
                .headers()
                .firstValue("Docker-Content-Digest")
                .orElseThrow { IllegalStateException("no Docker-Content-Digest header for $repo:$tag") }
        digestCache[repo to tag] = digest
        return digest
    }

    override fun deleteByDigest(
        repo: String,
        digest: String,
    ) {
        val resp =
            withAuth(repo, "pull,delete") { bearer ->
                val req =
                    HttpRequest
                        .newBuilder(URI("${apiBase(repo)}/manifests/$digest"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer $bearer")
                        .DELETE()
                        .build()
                http.send(req, BodyHandlers.ofString())
            }
        // 202 = deleted; 404 = already absent. A digest-delete is idempotent "ensure absent", so a 404
        // (e.g. concurrent GC after a re-point) means the desired end state already holds — not a failure.
        check(resp.statusCode() == 202 || resp.statusCode() == 404) {
            "delete failed for $repo@$digest: ${resp.statusCode()} ${resp.body()}"
        }
    }

    private fun pushedAt(
        repo: String,
        tag: String,
    ): Instant {
        val indexResp = get(repo, URI("${apiBase(repo)}/manifests/$tag"), "pull", accept = ACCEPT_INDEX)
        check(indexResp.statusCode() == 200) { "index fetch failed for $repo:$tag: ${indexResp.statusCode()}" }
        indexResp.headers().firstValue("Docker-Content-Digest").ifPresent { digestCache[repo to tag] = it }
        val index = mapper.readValue<ImageIndex>(indexResp.body())
        val amd64 =
            index.manifests.firstOrNull { it.platform?.os == "linux" && it.platform.architecture == "amd64" }
                ?: error("no linux/amd64 manifest in the index for $repo:$tag")

        val manifestResp = get(repo, URI("${apiBase(repo)}/manifests/${amd64.digest}"), "pull", accept = ACCEPT_MANIFEST)
        check(manifestResp.statusCode() == 200) {
            "child manifest fetch failed for $repo@${amd64.digest}: ${manifestResp.statusCode()}"
        }
        val configDigest = mapper.readValue<ImageManifest>(manifestResp.body()).config.digest

        val blobResp = get(repo, URI("${apiBase(repo)}/blobs/$configDigest"), "pull")
        check(blobResp.statusCode() == 200) { "config blob fetch failed for $repo@$configDigest: ${blobResp.statusCode()}" }
        return Instant.parse(mapper.readValue<ImageConfig>(blobResp.body()).created)
    }

    private fun apiBase(repo: String): String {
        val host = repo.substringBefore("/")
        val path = repo.substringAfter("/")
        return "$scheme://$host/v2/$path"
    }

    private fun get(
        repo: String,
        uri: URI,
        actions: String,
        accept: String? = null,
    ): HttpResponse<String> =
        withAuth(repo, actions) { bearer ->
            val builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Authorization", "Bearer $bearer").GET()
            if (accept != null) builder.header("Accept", accept)
            http.send(builder.build(), BodyHandlers.ofString())
        }

    /** Sends with a (possibly cached) bearer token; on a 401, evicts the cache and retries ONCE with a
     * freshly-authenticated token before giving up. */
    private fun withAuth(
        repo: String,
        actions: String,
        send: (String) -> HttpResponse<String>,
    ): HttpResponse<String> {
        val first = send(token(repo, actions))
        if (first.statusCode() != 401) return first
        tokenCache.remove(repo to actions)
        return send(token(repo, actions))
    }

    private fun token(
        repo: String,
        actions: String,
    ): String {
        val key = repo to actions
        tokenCache[key]?.let { if (Instant.now().isBefore(it.expiresAt)) return it.value }

        val host = repo.substringBefore("/")
        val path = repo.substringAfter("/")
        val (realm, service) = challenge(host)
        val basic = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        val req =
            HttpRequest
                .newBuilder(URI("$realm?service=$service&scope=repository:$path:$actions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Basic $basic")
                .GET()
                .build()
        val resp = http.send(req, BodyHandlers.ofString())
        check(resp.statusCode() == 200) { "token request failed for $repo ($actions): ${resp.statusCode()} ${resp.body()}" }
        val value = mapper.readValue<TokenResponse>(resp.body()).accessToken
        tokenCache[key] = CachedToken(value, Instant.now().plusSeconds(TOKEN_CACHE_SECONDS))
        return value
    }

    private fun challenge(host: String): Pair<String, String> {
        challengeCache[host]?.let { return it }
        val req = HttpRequest.newBuilder(URI("$scheme://$host/v2/")).timeout(REQUEST_TIMEOUT).GET().build()
        val resp = http.send(req, BodyHandlers.discarding())
        check(resp.statusCode() == 401) { "expected a 401 auth challenge from $host, got ${resp.statusCode()}" }
        val header =
            resp
                .headers()
                .firstValue("WWW-Authenticate")
                .orElseThrow { IllegalStateException("no WWW-Authenticate header in the 401 from $host") }
        val realm = REALM.find(header)?.groupValues?.get(1) ?: error("no realm in challenge from $host: $header")
        val service = SERVICE.find(header)?.groupValues?.get(1) ?: error("no service in challenge from $host: $header")
        return (realm to service).also { challengeCache[host] = it }
    }

    /** `null` means legitimately no next page; throws if the header signals one in an unparseable shape. */
    private fun nextLink(
        header: String,
        current: URI,
    ): URI? {
        val match = NEXT_LINK.find(header)
        if (match != null) return current.resolve(match.groupValues[1])
        check(!HAS_NEXT_INTENT.containsMatchIn(header)) {
            "Link header signals a next page but doesn't match the expected <url>; rel=\"next\" format: $header"
        }
        return null
    }

    /** Path + sorted, decoded query params — a page identity stable across param order / percent-encoding. */
    private fun canonicalKey(uri: URI): String {
        val query =
            (uri.rawQuery ?: "")
                .split("&")
                .filter { it.isNotEmpty() }
                .map { pair ->
                    val k = pair.substringBefore("=")
                    val v = pair.substringAfter("=", "")
                    URLDecoder.decode(k, Charsets.UTF_8) + "=" + URLDecoder.decode(v, Charsets.UTF_8)
                }.sorted()
                .joinToString("&")
        return "${uri.path}?$query"
    }

    companion object {
        private val REALM = Regex("""realm="([^"]*)"""")
        private val SERVICE = Regex("""service="([^"]*)"""")
        private val NEXT_LINK = Regex("""<([^>]+)>;\s*rel="next"""")
        private val HAS_NEXT_INTENT = Regex("""rel\s*=\s*"?next"?""")
    }
}

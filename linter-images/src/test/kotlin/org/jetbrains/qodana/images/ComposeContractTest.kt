package org.jetbrains.qodana.images

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class ComposeContractTest {
    // Test working dir is the module root (pinned in Phase 1) — resolve relative to it.
    private val mapper = YAMLMapper()

    private fun load(name: String): JsonNode = mapper.readTree(Path.of(name).readText())

    private val slugs =
        listOf(
            "qodana-jvm",
            "qodana-jvm-community",
            "qodana-android",
            "qodana-android-community",
            "qodana-clang",
            "qodana-python-community",
            "qodana-python",
            "qodana-js",
            "qodana-go",
            "qodana-php",
            "qodana-cdnet",
            "qodana-ruby",
            "qodana-ruby-3.2",
            "qodana-ruby-3.4",
            "qodana-rust",
            "qodana-dotnet",
            "qodana-cpp",
        )

    @Test
    fun `compose defines a service per linter with docker context, tooling context, release source, dev tag`() {
        val root = load("compose.yaml")
        val services = root["services"]
        assertEquals(slugs.toSet(), services.fieldNames().asSequence().toSet())
        for (slug in slugs) {
            val svc = services[slug]
            val build = svc["build"]
            assertEquals("docker", build["context"].asText(), "$slug context must be docker/")
            assertEquals(
                "images/$slug.dockerfile",
                build["dockerfile"].asText(),
                "$slug dockerfile must be context-relative",
            )
            assertEquals("release", build["args"]["CLI_SOURCE"].asText(), "compose.yaml is the release path")
            // Canonical: every service tags <slug>:dev (both compose files; smoke/dogfood reference :dev).
            assertEquals("$slug:dev", svc["image"].asText(), "$slug must tag <slug>:dev")
            // tooling is ALWAYS required (installDist tree) and is defined in the BASE compose file.
            assertTrue(
                build["additional_contexts"].has("tooling"),
                "$slug must define the tooling build context in compose.yaml",
            )
            // Public compose must NOT reference a cli context (CLI_SOURCE=release downloads it)...
            assertTrue(
                build["additional_contexts"]["cli"] == null,
                "$slug compose.yaml must not define a cli context (release path)",
            )
            // ...and must NOT define/reference a secret — a bare public build runs with
            // QODANA_READ_SPACE_PACKAGES_TOKEN unset (dist.dockerfile mounts it required=false).
            assertTrue(svc["secrets"] == null, "$slug must not reference a build secret in compose.yaml")
        }
        assertTrue(root["secrets"] == null, "compose.yaml must not declare any secrets (private overlay does)")
    }

    @Test
    fun `release path provides a non-empty CLI_RELEASE_BASE_URL so the inner CLI download cannot 404 silently`() {
        // Strengthening beyond the plan's verbatim test: install-cli forms the asset URL as
        // ${CLI_RELEASE_BASE_URL}/qodana_<os>_<arch>.tar.gz, so a missing/blank base URL is a
        // guaranteed build break that the structural assertions above would not catch.
        val build = load("compose.yaml")["services"]["qodana-jvm"]["build"]
        val baseUrl = build["args"]["CLI_RELEASE_BASE_URL"]
        assertTrue(
            baseUrl != null && baseUrl.asText().isNotBlank(),
            "qodana-jvm must set a non-empty CLI_RELEASE_BASE_URL",
        )
        assertTrue(
            baseUrl.asText().startsWith("https://"),
            "CLI_RELEASE_BASE_URL must be an absolute https URL, was: '${baseUrl.asText()}'",
        )
    }

    @Test
    fun `clang layers the inner CLI onto the tools stage (no dist), via a compose build arg`() {
        // Clang/cdnet have NO dist stage: base.dockerfile defaults CLI_BASE_STAGE=dist, so they MUST
        // override it to `tools` (a build ARG, not an .env key) or the cli stage's `FROM
        // ${CLI_BASE_STAGE}` resolves to a non-existent `dist` stage and the build breaks.
        val root = load("compose.yaml")["services"]
        for (slug in listOf("qodana-clang", "qodana-cdnet")) {
            val args = root[slug]["build"]["args"]
            assertEquals("tools", args["CLI_BASE_STAGE"].asText(), "$slug must build CLI onto the tools stage")
        }
        // jvm/jvm-community/android/android-community/python-community/python have a dist stage; they
        // must NOT override CLI_BASE_STAGE (it stays the `dist` default).
        for (slug in listOf(
            "qodana-jvm",
            "qodana-jvm-community",
            "qodana-android",
            "qodana-android-community",
            "qodana-python-community",
            "qodana-python",
            "qodana-js",
            "qodana-go",
            "qodana-php",
            "qodana-ruby",
            "qodana-ruby-3.2",
            "qodana-ruby-3.4",
            "qodana-rust",
            "qodana-dotnet",
            "qodana-cpp",
        )) {
            val args = root[slug]["build"]["args"]
            assertTrue(args["CLI_BASE_STAGE"] == null, "$slug must not override CLI_BASE_STAGE (defaults to dist)")
        }
        // PRIVILEGED_BASE_STAGE: base.dockerfile defaults it to `clang-toolchain`. cdnet's privileged
        // layer sits on the .NET toolchain, so cdnet MUST override it to `dotnet-toolchain`. clang relies
        // on the global default and jvm/android have no privileged stage — none of them may set it.
        assertEquals(
            "dotnet-toolchain",
            root["qodana-cdnet"]["build"]["args"]["PRIVILEGED_BASE_STAGE"].asText(),
            "cdnet's privileged layer sits on the .NET toolchain",
        )
        // cpp is privileged but — like clang — its privileged layer sits on `clang-toolchain` (node+eslint
        // append there), which is base.dockerfile's global PRIVILEGED_BASE_STAGE default, so cpp sets no
        // override either. Unlike clang it HAS a dist that FROMs privileged: DIST_BASE_STAGE=privileged is
        // an .env KEY (no clobber), so it must be ABSENT from the compose args (CppEnvContractTest asserts
        // it lives in the .env).
        for (slug in listOf("qodana-jvm", "qodana-android", "qodana-clang", "qodana-rust", "qodana-cpp")) {
            val args = root[slug]["build"]["args"]
            assertTrue(
                args["PRIVILEGED_BASE_STAGE"] == null,
                "$slug must not override PRIVILEGED_BASE_STAGE (clang relies on the global clang-toolchain default)",
            )
        }
        assertTrue(
            root["qodana-cpp"]["build"]["args"]["DIST_BASE_STAGE"] == null,
            "cpp DIST_BASE_STAGE is an .env key (no clobber), not a compose build arg",
        )
        // Ruby is the FIRST dist+privileged image: its privileged layer sits on `base` (node+eslint+ruby
        // are in-place there), so PRIVILEGED_BASE_STAGE=base is a build ARG (base.dockerfile defaults it
        // to clang-toolchain, which would clobber an .env value). DIST_BASE_STAGE=privileged is an .env
        // KEY (no clobber — base.dockerfile does not declare it), NOT a compose build arg, so it must be
        // ABSENT here (EnvContractTest asserts it lives in the .env). CLI_BASE_STAGE stays the `dist`
        // default (ruby has a dist) — asserted in the no-override loop above.
        for (slug in listOf("qodana-ruby", "qodana-ruby-3.2", "qodana-ruby-3.4")) {
            val args = root[slug]["build"]["args"]
            assertEquals(
                "base",
                args["PRIVILEGED_BASE_STAGE"].asText(),
                "$slug privileged layers onto base (the in-place node+eslint+ruby toolchains)",
            )
            assertTrue(
                args["DIST_BASE_STAGE"] == null,
                "$slug DIST_BASE_STAGE is an .env key (no clobber), not a compose build arg",
            )
        }
    }

    @Test
    fun `dotnet layers privileged onto the dotnet-toolchain via a build arg, with DIST_BASE_STAGE as an env key`() {
        // dotnet is the FIRST dist+privileged+dotnet-toolchain image: its privileged layer sits on the
        // .NET toolchain (like cdnet), so PRIVILEGED_BASE_STAGE=dotnet-toolchain is a build ARG
        // (base.dockerfile defaults it to clang-toolchain, which would clobber an .env value).
        // DIST_BASE_STAGE=privileged is an .env KEY (no clobber — base.dockerfile does not declare it),
        // NOT a compose build arg, so it must be ABSENT here (DotnetEnvContractTest asserts it lives in
        // the .env). CLI_BASE_STAGE stays the `dist` default (dotnet has a dist) — asserted in the
        // no-CLI_BASE_STAGE-override loop above.
        val args = load("compose.yaml")["services"]["qodana-dotnet"]["build"]["args"]
        assertEquals(
            "dotnet-toolchain",
            args["PRIVILEGED_BASE_STAGE"].asText(),
            "dotnet's privileged layer sits on the .NET toolchain (the cdnet convention)",
        )
        assertTrue(
            args["DIST_BASE_STAGE"] == null,
            "dotnet DIST_BASE_STAGE is an .env key (no clobber), not a compose build arg",
        )
    }

    @Test
    fun `qodana-js passes the QODANA_UID-GID build args matching phase-0-decisions (dhi-node uid-1000 conflict)`() {
        // The dhi.io/node base ships a `node` user at uid/gid 1000, so qodana shifts to 1001. The override
        // is a COMPOSE BUILD ARG (not an .env key): dockerfile-x's INCLUDE_ARGS emits each .env key as an
        // `ARG NAME="val"` default that base.dockerfile's own `ARG QODANA_UID=1000` (emitted later) clobbers
        // back to 1000; a --build-arg always wins. This guards that contract + byte-identity to phase-0.
        val args = load("compose.yaml")["services"]["qodana-js"]["build"]["args"]
        val uid = args["QODANA_UID"]
        val gid = args["QODANA_GID"]
        assertTrue(uid != null && gid != null, "qodana-js must pass QODANA_UID/QODANA_GID build args")

        val d = Path.of("docs/phase-0-decisions.md").readText()

        fun pin(k: String) =
            Regex("""^\s*$k\s*=\s*(\S+)""", RegexOption.MULTILINE).find(d)?.groupValues?.get(1)
                ?: error("$k not recorded in phase-0-decisions.md")
        assertEquals(pin("QODANA_JS_UID"), uid.asText(), "qodana-js QODANA_UID must match phase-0-decisions")
        assertEquals(pin("QODANA_JS_GID"), gid.asText(), "qodana-js QODANA_GID must match phase-0-decisions")
        // No other service overrides the uid (they keep base.dockerfile's default 1000).
        for (slug in slugs.filter { it != "qodana-js" }) {
            val a = load("compose.yaml")["services"][slug]["build"]["args"]
            assertTrue(
                a["QODANA_UID"] == null && a["QODANA_GID"] == null,
                "$slug must not override QODANA_UID/GID (keeps base.dockerfile's default 1000)",
            )
        }
    }

    @Test
    fun `clang release-path CLI_VERSION matches the release tag (the tool asset name embeds it)`() {
        // The qodana-clang/qodana-cdnet TOOL asset is `<binary>_<CLI_VERSION>_linux_amd64`
        // (CliArtifactResolver), so on --source release CLI_VERSION MUST equal the version segment of the
        // compose tag, or the download 404s. (Cli-kind jvm/android have no version in their
        // `qodana_<os>_<arch>.tar.gz` name, so they are exempt.) Guards the W2 path where the feed-less
        // linters pull their inner CLI from the nightly release.
        for (slug in listOf("qodana-clang", "qodana-cdnet")) {
            val build = load("compose.yaml")["services"][slug]["build"]
            val baseUrl = build["args"]["CLI_RELEASE_BASE_URL"].asText()
            val tag = baseUrl.substringAfterLast('/')
            val tagVersion = tag.removePrefix("v")

            val cliVersion =
                imagesEnv(slug)["CLI_VERSION"]
                    ?: error("$slug.env must set CLI_VERSION")
            assertEquals(
                tagVersion,
                cliVersion,
                "$slug CLI_VERSION must equal the CLI_RELEASE_BASE_URL tag version, or the tool asset 404s",
            )
        }
    }

    /**
     * Parse a per-slug `.env` into a key→value map. Loose by design (no shape/duplicate checks) —
     * EnvContractTest is the strict validator of the same files and runs in this suite, so a malformed
     * or duplicate-keyed `.env` fails there; this helper only needs to read one already-validated value.
     */
    private fun imagesEnv(slug: String): Map<String, String> =
        Path
            .of("docker/images/$slug.env")
            .readText()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val i = line.indexOf('=')
                line.substring(0, i) to line.substring(i + 1)
            }

    @Test
    fun `private overlay gives every image the single Space-packages read token`() {
        // ONE token (QODANA_READ_SPACE_PACKAGES_TOKEN) reads every private JetBrains Space package: the
        // internal IDE feed (dist images mount space_packages_token in dist.dockerfile, required=false)
        // AND the qodana-cli-deps mirror (clang/cdnet mount it in tools/resharper-clt.dockerfile,
        // required=true). One secret id, one env source.
        val root = load("compose.private.yaml")
        val services = root["services"]
        assertEquals(slugs.toSet(), services.fieldNames().asSequence().toSet())

        fun secretsOf(slug: String) = services[slug]["build"]["secrets"].asSequence().map { it.asText() }.toSet()

        for (slug in slugs) {
            assertEquals(
                setOf("space_packages_token"),
                secretsOf(slug),
                "$slug must reference the single space_packages_token secret",
            )
        }
        val declared = root["secrets"]
        assertEquals(
            setOf("space_packages_token"),
            declared.fieldNames().asSequence().toSet(),
            "the overlay declares exactly one Space-packages read secret",
        )
        assertEquals(
            "QODANA_READ_SPACE_PACKAGES_TOKEN",
            declared["space_packages_token"]["environment"].asText(),
        )
    }

    @Test
    fun `compose ci override switches to context source and adds only the cli context, no tag change`() {
        val services = load("compose.ci.yaml")["services"]
        assertEquals(slugs.toSet(), services.fieldNames().asSequence().toSet())
        for (slug in slugs) {
            val svc = services[slug]
            val build = svc["build"]
            assertEquals("context", build["args"]["CLI_SOURCE"].asText(), "ci override is the context path")
            assertTrue(build["additional_contexts"].has("cli"), "$slug ci must add the cli context")
            // ci override must NOT change the tag — it stays <slug>:dev from compose.yaml.
            val img = svc["image"]
            assertTrue(img == null || img.asText() == "$slug:dev", "$slug ci must not change the :dev tag")
        }
    }
}

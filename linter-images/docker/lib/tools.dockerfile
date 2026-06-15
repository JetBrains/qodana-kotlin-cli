# tools — fetch clang-tidy (qodana-cli-deps mirror, NOT the IDE feed) into cacheDir/tools.
# No provision-dist: clang-tidy has no IDE dist/feed. Pre-extracted on disk so the path
# matches the runtime cacheDir/tools the qodana-clang entrypoint resolves (and --cache-dir).
#
# The mirror is PRIVATE (packages.jetbrains.team). `ADD` cannot carry credentials, so this fetches
# with `curl` from a build secret (id=qodana_cli_deps_token) using `Authorization: Bearer` — the form
# the canonical qodana-cli `download-clang-tidy.go` uses for this exact Space mirror (a JB Space
# bearer token). The secret id MUST stay consistent across the `--mount` here, compose.private.yaml,
# and CI (Phase 5 wires QODANA_CLI_DEPS_TOKEN into the build). The mirror path prepends `v` to the
# package tag: `${CLANG_TIDY_MIRROR}/v${CLANG_TIDY_VERSION}/clang-tidy-linux-${CLI_ARCH}.tar.gz`.
# sha256 is verified fail-closed against CLANG_TIDY_SHA256 (defaulted below to the amd64 archive for
# the pinned CLANG_TIDY_VERSION; Renovate's packageRule PR note prompts a refresh on bump). The token
# is read from the secret file with xtrace OFF (`set +x`) around the read+curl so it never reaches the
# build log, and the BuildKit secret mount keeps it out of any image layer. curl is provided by base.
# Consumes: CLANG_TIDY_MIRROR CLANG_TIDY_VERSION CLANG_TIDY_SHA256 CLI_ARCH CLANG_TIDY_TOOLS_DIR
ARG CLANG_TIDY_MIRROR
ARG CLANG_TIDY_VERSION
# Default sha256 of clang-tidy-linux-amd64.tar.gz for the pinned CLANG_TIDY_VERSION (this image is
# amd64-only). Not an .env key (EnvContractTest enforces the clang key set); overridable per build.
ARG CLANG_TIDY_SHA256=dea43a4f013db12fd352df6aac2884a760c53dd4eeac1f2e7114a1e74846bf95
ARG CLI_ARCH=amd64
ARG CLANG_TIDY_TOOLS_DIR=/data/cache/tools

FROM privileged AS tools
ARG CLANG_TIDY_MIRROR
ARG CLANG_TIDY_VERSION
ARG CLANG_TIDY_SHA256
ARG CLI_ARCH
ARG CLANG_TIDY_TOOLS_DIR

# hadolint ignore=DL4006
RUN --mount=type=secret,id=qodana_cli_deps_token,required=true <<-EOT
	set -eu
	: "${CLANG_TIDY_SHA256:?CLANG_TIDY_SHA256 must be set to verify the clang-tidy archive}"
	# Read the token + run the authenticated curl with xtrace OFF so the Bearer header (and thus the
	# token) never reaches the build log. Re-enable xtrace immediately after for the rest of the step.
	set +x
	TOKEN="$(cat /run/secrets/qodana_cli_deps_token)"
	curl -fsSL -H "Authorization: Bearer ${TOKEN}" \
		-o /tmp/clang-tidy.tar.gz \
		"${CLANG_TIDY_MIRROR}/v${CLANG_TIDY_VERSION}/clang-tidy-linux-${CLI_ARCH}.tar.gz"
	unset TOKEN
	set -x
	echo "${CLANG_TIDY_SHA256}  /tmp/clang-tidy.tar.gz" | sha256sum -c -
	mkdir -p "${CLANG_TIDY_TOOLS_DIR}"
	tar -xzf /tmp/clang-tidy.tar.gz -C "${CLANG_TIDY_TOOLS_DIR}"
	rm -f /tmp/clang-tidy.tar.gz
	chown -R 1000:1000 "${CLANG_TIDY_TOOLS_DIR}"
EOT

# Make the tools path discoverable for the runtime cacheDir contract.
ENV QODANA_TOOLS_DIR=${CLANG_TIDY_TOOLS_DIR}

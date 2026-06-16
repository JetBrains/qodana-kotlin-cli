# tools — fetch clang-tidy (qodana-cli-deps mirror, NOT the IDE feed) and INSTALL it on PATH.
# No provision-dist: clang-tidy has no IDE dist/feed. This is installed tooling, NOT a cache:
# it is extracted to /opt/qodana-clang (the mirror tarball lays out bin/clang-tidy, yielding
# /opt/qodana-clang/bin/clang-tidy) and that bin/ is put on PATH, beside the IDE dist convention
# (QODANA_DIST=/opt/idea). The qodana-clang entrypoint resolves clang-tidy by name on PATH, so the
# scan works under `network: none` and is independent of the writable /data/cache scratch mount.
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
ARG CLANG_TIDY_TOOLS_DIR=/opt/qodana-clang

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

# Put the installed clang-tidy on PATH so the qodana-clang entrypoint resolves it by name. The
# include order (tools before cli/runtime) and the lack of any later PATH override keep this in the
# final image. /opt/qodana-clang is fixed (not ${CLANG_TIDY_TOOLS_DIR}) so the PATH entry stays valid
# even if a build overrides the ARG.
ENV PATH=/opt/qodana-clang/bin:$PATH

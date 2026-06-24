# cli — install the inner Qodana CLI binary (release-default or context-CI).
# release: image-tool downloads + checksums the published binary (no cli context needed).
# context: image-tool reads the from-tree binary via the bind-mounted `cli` context. That context
#          stages the BARE binary at its root (release-staging/qodana), so --context-path is the
#          FILE /cli-src/${CLI_BINARY}, not a directory.
# Consumes: CLI_BINARY CLI_SOURCE CLI_VERSION CLI_OS CLI_RELEASE_BASE_URL
#           CLI_BASE_STAGE JDK_BUILDER_IMAGE. Arch comes from BuildKit's TARGETARCH (amd64/arm64).
ARG CLI_BINARY=qodana
ARG CLI_SOURCE=release
ARG CLI_VERSION
ARG CLI_OS=linux
ARG CLI_RELEASE_BASE_URL=https://github.com/JetBrains/qodana-kotlin-cli/releases/download
# JDK_BUILDER_IMAGE + CLI_BASE_STAGE defaults + global scope live in lib/base.dockerfile (FROM-ARGs
# must be declared before the first FROM); the FROMs below consume those global values.

# Empty default `cli` stage so `--mount=from=cli` resolves on the release path with NO `cli` named
# context (BuildKit would otherwise try to PULL an image `cli:latest`, and `required=` is not a valid
# key for type=bind). compose.ci.yaml passes a `cli` named context that SHADOWS this empty stage; on
# the public release path the bind sees an empty dir, which install-cli ignores under --source release.
FROM scratch AS cli

FROM ${JDK_BUILDER_IMAGE} AS cli-builder
# CLI_BINARY/CLI_VERSION/CLI_OS are global .env ARGs; the bare re-declarations inherit them. TARGETARCH
# is BuildKit-provided (the build platform's arch). CLI_SOURCE/CLI_RELEASE_BASE_URL are build ARGs
# (compose passes them) but NOT .env keys, so their pre-FROM defaults are inter-stage (not global) —
# re-default them HERE so the `set -u` RUN cannot trip "parameter not set" on a bare `docker build`.
ARG CLI_BINARY
ARG CLI_SOURCE=release
ARG CLI_VERSION
ARG CLI_OS
ARG TARGETARCH
ARG CLI_RELEASE_BASE_URL=https://github.com/JetBrains/qodana-kotlin-cli/releases/download
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends curl
	rm -rf /var/lib/apt/lists/*
EOT
# image-tool from the `tooling` context; the from-tree CLI from the `cli` context (an empty `cli`
# stage on the release path, the from-tree binary when compose.ci.yaml passes a `cli` context).
# install-cli reads /cli-src ONLY under --source context, and fails loudly there if the bind is empty.
RUN --mount=type=bind,from=tooling,target=/tooling \
	--mount=type=bind,from=cli,target=/cli-src <<-EOT
	set -eux
	mkdir -p /staging/bin
	/tooling/bin/image-tool install-cli \
		--binary "${CLI_BINARY}" \
		--source "${CLI_SOURCE}" \
		--version "${CLI_VERSION}" \
		--os "${CLI_OS}" \
		--arch "${TARGETARCH}" \
		--release-base-url "${CLI_RELEASE_BASE_URL}" \
		--context-path "/cli-src/${CLI_BINARY}" \
		--target "/staging/bin/${CLI_BINARY}"
	chmod 0755 "/staging/bin/${CLI_BINARY}"
EOT

# Final assembled stage is `cli-installed` (NOT `cli`, which is the empty mount-fallback stage above).
# runtime.dockerfile layers onto `cli-installed`.
FROM ${CLI_BASE_STAGE} AS cli-installed
# NO default: a stage-local `ARG CLI_BINARY=qodana` would SHADOW the INCLUDE_ARGS global (Docker uses the
# stage default over the global), making the COPY below resolve to qodana even for the clang image. Bare
# `ARG` inherits the global value (qodana-clang for clang), matching the cli-builder stage above.
ARG CLI_BINARY
# Bare (no default) for the SAME reason: inherit the global QODANA_UID/GID (the language-base override).
ARG QODANA_UID
ARG QODANA_GID
COPY --from=cli-builder --chown=${QODANA_UID}:${QODANA_GID} /staging/bin/${CLI_BINARY} /usr/local/bin/${CLI_BINARY}

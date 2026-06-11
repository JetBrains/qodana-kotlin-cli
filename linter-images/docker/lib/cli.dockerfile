# cli — install the inner Qodana CLI binary (release-default or context-CI).
# release: image-tool downloads + checksums the published binary (no cli context needed).
# context: image-tool reads the from-tree binary via the bind-mounted `cli` context. That context
#          stages the BARE binary at its root (release-staging/qodana), so --context-path is the
#          FILE /cli-src/${CLI_BINARY}, not a directory.
# Consumes: CLI_BINARY CLI_SOURCE CLI_VERSION CLI_OS CLI_ARCH CLI_RELEASE_BASE_URL
#           CLI_BASE_STAGE JDK_BUILDER_IMAGE
ARG CLI_BINARY=qodana
ARG CLI_SOURCE=release
ARG CLI_VERSION
ARG CLI_OS=linux
ARG CLI_ARCH=amd64
ARG CLI_RELEASE_BASE_URL=https://github.com/JetBrains/qodana-kotlin-cli/releases/download
ARG CLI_BASE_STAGE=dist
# JDK builder default: eclipse-temurin 25 (Ubuntu-based: ships tar + coreutils sha256sum + apt),
# pinned by manifest-list digest. NOT a .env key — overridable per build (Renovate-tracked).
# Kept byte-identical to lib/dist.dockerfile's default.
ARG JDK_BUILDER_IMAGE=eclipse-temurin:25-jdk@sha256:edb3aa0f621796d8f5f9d602c7611ffdf015cd89e6ddda1894d85a3a99d170a8

# Empty default `cli` stage so `--mount=from=cli` resolves on the release path with NO `cli` named
# context (BuildKit would otherwise try to PULL an image `cli:latest`, and `required=` is not a valid
# key for type=bind). compose.ci.yaml passes a `cli` named context that SHADOWS this empty stage; on
# the public release path the bind sees an empty dir, which install-cli ignores under --source release.
FROM scratch AS cli

FROM ${JDK_BUILDER_IMAGE} AS cli-builder
ARG CLI_BINARY
ARG CLI_SOURCE
ARG CLI_VERSION
ARG CLI_OS
ARG CLI_ARCH
ARG CLI_RELEASE_BASE_URL
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
		--arch "${CLI_ARCH}" \
		--release-base-url "${CLI_RELEASE_BASE_URL}" \
		--context-path "/cli-src/${CLI_BINARY}" \
		--target "/staging/bin/${CLI_BINARY}"
	chmod 0755 "/staging/bin/${CLI_BINARY}"
EOT

# Final assembled stage is `cli-installed` (NOT `cli`, which is the empty mount-fallback stage above).
# runtime.dockerfile layers onto `cli-installed`.
FROM ${CLI_BASE_STAGE} AS cli-installed
ARG CLI_BINARY=qodana
COPY --from=cli-builder --chown=1000:1000 /staging/bin/${CLI_BINARY} /usr/local/bin/${CLI_BINARY}

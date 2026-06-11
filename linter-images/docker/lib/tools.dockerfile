# tools — fetch clang-tidy (qodana-cli-deps mirror, NOT the IDE feed) into cacheDir/tools.
# No provision-dist: clang-tidy has no IDE dist/feed. Pre-extracted on disk so the path
# matches the runtime cacheDir/tools the qodana-clang entrypoint resolves (and --cache-dir).
# Consumes: CLANG_TIDY_MIRROR CLANG_TIDY_VERSION CLANG_TIDY_SHA256 CLI_ARCH CLANG_TIDY_TOOLS_DIR
ARG CLANG_TIDY_MIRROR
ARG CLANG_TIDY_VERSION
ARG CLANG_TIDY_SHA256
ARG CLI_ARCH=amd64
ARG CLANG_TIDY_TOOLS_DIR=/data/cache/tools

FROM privileged AS tools
ARG CLANG_TIDY_MIRROR
ARG CLANG_TIDY_VERSION
ARG CLANG_TIDY_SHA256
ARG CLI_ARCH
ARG CLANG_TIDY_TOOLS_DIR

# sha-verified declarative fetch (DL3020 waived in .hadolint.yaml).
ADD --checksum=sha256:${CLANG_TIDY_SHA256} \
	${CLANG_TIDY_MIRROR}/clang-tidy-${CLANG_TIDY_VERSION}-linux-${CLI_ARCH}.tar.gz \
	/tmp/clang-tidy.tar.gz
RUN <<-EOT
	set -eux
	mkdir -p "${CLANG_TIDY_TOOLS_DIR}"
	tar -xzf /tmp/clang-tidy.tar.gz -C "${CLANG_TIDY_TOOLS_DIR}"
	rm -f /tmp/clang-tidy.tar.gz
	chown -R 1000:1000 "${CLANG_TIDY_TOOLS_DIR}"
EOT

# Make the tools path discoverable for the runtime cacheDir contract.
ENV QODANA_TOOLS_DIR=${CLANG_TIDY_TOOLS_DIR}

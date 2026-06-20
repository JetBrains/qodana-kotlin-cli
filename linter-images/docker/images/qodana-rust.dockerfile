# syntax=docker.io/devthefuture/dockerfile-x:1.6.0@sha256:000d1ae882609bf9a7a3aa4647370d55ffb769580ea5895987192996ffed159f
INCLUDE_ARGS images/qodana-rust.env
INCLUDE lib/base.dockerfile
INCLUDE lib/toolchain/rust.dockerfile
INCLUDE lib/dist.dockerfile
INCLUDE lib/cli.dockerfile
INCLUDE lib/runtime.dockerfile

# The bundled JBR's font manager dlopens libfreetype.so.6 the moment RustRover renders the Cargo-sync
# build view during project-model configuration; absent → NoClassDefFoundError(sun.awt.X11FontManager)
# → UnsatisfiedLinkError, which fails the sync, leaves the project unconfigured, and trips the linter's
# own qd.rust.configuration.timeout.minutes (QD-15111). RustRover is JBR-only (no .NET backend), so —
# unlike qodana-cpp — only the fonts are needed, not the ICU/SSL runtime libs. rust-local (not in
# lib/toolchain/rust.dockerfile): the dep is the dist's, not the toolchain's, and this keeps the other
# images byte-unchanged. Bare ARGs inherit the base.dockerfile globals. The runtime stage has already
# dropped to the qodana user, so switch to root for apt, then drop back so the shipped image scans unprivileged.
ARG QODANA_UID
ARG QODANA_GID
USER 0
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends fontconfig libfreetype6
	rm -rf /var/lib/apt/lists/*
EOT
USER ${QODANA_UID}:${QODANA_GID}

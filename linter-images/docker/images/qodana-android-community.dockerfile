# syntax=docker.io/devthefuture/dockerfile-x:1.6.0@sha256:000d1ae882609bf9a7a3aa4647370d55ffb769580ea5895987192996ffed159f
INCLUDE_ARGS images/qodana-android-community.env
INCLUDE lib/base.dockerfile
INCLUDE lib/toolchain/android.dockerfile
INCLUDE lib/dist.dockerfile
INCLUDE lib/cli.dockerfile
INCLUDE lib/runtime.dockerfile

# JBR font-manager native libs (QD-15265): Android Studio's bundled JBR dlopens libfreetype.so.6 when it
# renders the Gradle sync build view during project-model configuration; absent -> NoClassDefFoundError
# (sun.awt.X11FontManager) in the import coroutine, which intermittently leaves "Project opening"
# awaiting forever -> the e2e hang-timeout kills the scan (exit 124). Same fix as qodana-cpp (QD-15107)
# and qodana-rust (QD-15111); Android Studio is JBR-only, so only the fonts are needed. Installed as
# root (apt), then USER drops back so the shipped image still scans unprivileged.
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

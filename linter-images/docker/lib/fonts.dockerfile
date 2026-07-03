# JBR font-manager native libs, shared by every bundled-JBR IDE image that renders a build-tool sync view
# during project-model configuration: the JVM/Android family (IDEA / Android Studio, Maven/Gradle) and
# Rust (RustRover, Cargo). Their JBR dlopens libfreetype.so.6 while rendering that view; absent, the font
# init throws and headless project-open can hang instead of failing. qodana-cpp keeps its own inline copy
# (it also needs the .NET runtime libs). Must be the LAST include so its unprivileged USER is the image's
# effective final USER; bare ARGs inherit base's UID/GID — a `=1000` default would shadow the INCLUDE_ARGS
# override.
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

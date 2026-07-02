# JBR font-manager native libs, shared by the JVM/Android family (IDEA / Android Studio): their bundled
# JBR dlopens libfreetype.so.6 while rendering the Maven/Gradle sync build view during project-model
# configuration; absent, the font init throws and headless project-open can hang instead of failing.
# Terminal include (continues the runtime stage); bare ARGs inherit base's UID/GID -- a `=1000` default
# would shadow the INCLUDE_ARGS override (the cli.dockerfile trap).
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

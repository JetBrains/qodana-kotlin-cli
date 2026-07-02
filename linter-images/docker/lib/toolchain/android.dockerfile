# toolchain/android — Android SDK cmdline-tools + Corretto 11/17. Multiarch (amd64+arm64), no Node.
# Consumes: ANDROID_SDK_VERSION ANDROID_SDK_SHA256 CORRETTO11_IMAGE CORRETTO17_IMAGE DEVICEID
ARG ANDROID_SDK_VERSION
ARG ANDROID_SDK_SHA256
ARG CORRETTO11_IMAGE
ARG CORRETTO17_IMAGE
ARG DEVICEID=qodana

# Corretto runtimes pinned by digest (declarative, no GPG branching).
FROM ${CORRETTO11_IMAGE} AS corretto11
FROM ${CORRETTO17_IMAGE} AS corretto17

FROM base AS android-toolchain
ARG ANDROID_SDK_VERSION
ARG ANDROID_SDK_SHA256
ARG DEVICEID

COPY --from=corretto11 --chown=1000:1000 /usr/lib/jvm /opt/java/corretto11
COPY --from=corretto17 --chown=1000:1000 /usr/lib/jvm /opt/java/corretto17

ENV ANDROID_HOME=/opt/android-sdk \
	ANDROID_SDK_ROOT=/opt/android-sdk \
	DEVICEID=${DEVICEID}

# cmdline-tools: sha-verified declarative fetch (DL3020 waived in .hadolint.yaml).
ADD --checksum=sha256:${ANDROID_SDK_SHA256} \
	https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_SDK_VERSION}_latest.zip \
	/tmp/cmdline-tools.zip
# DL4006 (the `yes |` pipe below): set -eux under /bin/sh -e handles failures, as in node.dockerfile.
# hadolint ignore=SC2015,DL4006
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends unzip
	mkdir -p "${ANDROID_HOME}/cmdline-tools"
	unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools"
	mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"
	rm -f /tmp/cmdline-tools.zip
	# sdkmanager is a Java tool; point it at the bundled Corretto 17. Use the real JDK dir — the
	# `java`/`jre` symlinks dangle post-COPY (they target /etc/alternatives, which isn't copied).
	export JAVA_HOME=/opt/java/corretto17/java-17-amazon-corretto
	export PATH="${JAVA_HOME}/bin:${PATH}"
	# Pre-accept SDK licenses so a customer bootstrap's sdkmanager runs unprompted. NOTHING else is
	# installed here: no platform-tools (x86_64 adb/fastboot — device tooling a static scan never runs;
	# unrunnable on arm64) and no build-tools/platform (the customer provisions the compile SDK at scan
	# time). Keeps the toolchain arch-neutral: cmdline-tools is Java, Corretto is a multi-arch index.
	yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
	# `|| true` absorbs sdkmanager's SIGPIPE(141) when `yes` writes past the last prompt (benign). But it
	# also masks a genuine failure (broken JDK, ENOSPC, crash) — and since platform-tools no longer runs
	# here, no later sdkmanager op would surface it. So assert the licenses were actually written and fail
	# LOUD otherwise; sdkmanager --licenses records accepted hashes under $ANDROID_HOME/licenses.
	[ -n "$(ls -A "${ANDROID_HOME}/licenses" 2>/dev/null)" ] || { echo "sdkmanager --licenses accepted nothing" >&2; exit 1; }
	chown -R 1000:1000 "${ANDROID_HOME}" /opt/java
	apt-get purge -y unzip
	apt-get autoremove -y
	rm -rf /var/lib/apt/lists/*
EOT

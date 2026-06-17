# base — dhi.io hardened OS, qodana user, writable dirs, QODANA_* env, locale, OS glue.
# Consumes (from images/<slug>.env): QD_BASE_IMAGE QD_DIST
#
# GLOBAL build-ARG scope: base is always the FIRST include, so any ARG a later include interpolates
# in a `FROM` must be declared HERE, before the first FROM — Docker only resolves FROM-ARGs from the
# global pre-FROM scope (a default declared inside a later stage yields a blank base name). These are
# build ARGs, NOT .env keys; override per build (e.g. compose CLI_BASE_STAGE=tools for clang).
ARG JDK_BUILDER_IMAGE=eclipse-temurin:25-jdk@sha256:edb3aa0f621796d8f5f9d602c7611ffdf015cd89e6ddda1894d85a3a99d170a8
ARG CLI_BASE_STAGE=dist
ARG PRIVILEGED_BASE_STAGE=clang-toolchain
ARG QD_BASE_IMAGE
FROM ${QD_BASE_IMAGE} AS base
ARG QD_DIST=/opt/idea

# The dhi.io hardened base defaults to USER 65532 (nonroot), so apt + user/dir creation below would
# hit "Permission denied" on /var/lib/apt. Switch to root for image construction; runtime.dockerfile
# drops back to the unprivileged qodana user (1000) for scan-time, so the shipped image is non-root.
# Use the NUMERIC uid 0 — the hardened base's /etc/passwd has no `root` name entry (only `nonroot`),
# so `USER root` fails with "unable to find user root".
USER 0

# OS glue: git for VCS-aware scans; curl for runtime fetches; jq for entrypoint JSON wrangling;
# ca-certificates + locales for TLS and UTF-8. NO gnupg — signature verification happens only in
# the dist-builder stage (which apt-installs gnupg there); nothing at scan-time needs gpg.
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends \
		ca-certificates \
		curl \
		git \
		jq \
		locales
	sed -i 's/^# *\(en_US.UTF-8\)/\1/' /etc/locale.gen
	locale-gen en_US.UTF-8
	rm -rf /var/lib/apt/lists/*
EOT

ENV LANG=en_US.UTF-8 \
	LANGUAGE=en_US:en \
	LC_ALL=en_US.UTF-8

# qodana user (uid/gid 1000 — matches engine container conventions) + writable dirs.
RUN <<-EOT
	set -eux
	groupadd --gid 1000 qodana
	useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash qodana
	mkdir -p /data/project /data/cache /data/results "${QD_DIST}"
	chown -R 1000:1000 /data "${QD_DIST}"
EOT

# QODANA_* runtime contract (paths the inner CLI reads).
ENV QODANA_DIST=${QD_DIST} \
	QODANA_CONF=/data/project/qodana.yaml \
	QODANA_DOCKER=true \
	QODANA_ENV=cli

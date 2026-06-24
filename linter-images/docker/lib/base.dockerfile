# base — dhi.io hardened OS, qodana user, writable dirs, QODANA_* env, locale, OS glue.
# Consumes (from images/<slug>.env): QD_BASE_IMAGE QD_DIST
#
# GLOBAL build-ARG scope: base is always the FIRST include, so any ARG a later include interpolates
# in a `FROM` must be declared HERE, before the first FROM — Docker only resolves FROM-ARGs from the
# global pre-FROM scope (a default declared inside a later stage yields a blank base name). These are
# build ARGs, NOT .env keys; override per build (e.g. compose CLI_BASE_STAGE=tools for clang).
ARG JDK_BUILDER_IMAGE=eclipse-temurin:25-jdk@sha256:dfc0093e3dbf43dae57827111c6e374f5b44fac19a9451584b2b336b81474d64
ARG CLI_BASE_STAGE=dist
ARG PRIVILEGED_BASE_STAGE=clang-toolchain
ARG QD_BASE_IMAGE
# qodana user uid/gid — default 1000 so the existing debian-base images (jvm/android/python/clang) are
# byte-identical. A DHI language base whose own user occupies 1000 (the node base ships `node` at 1000)
# overrides these via its .env INCLUDE_ARGS (js → 1001). DECLARED GLOBAL (pre-FROM) so the bare
# re-declarations in base/dist/cli/runtime inherit this default; a stage-local `=1000` would SHADOW the
# INCLUDE_ARGS override (cli.dockerfile:62-67), so the default lives here and NOWHERE else.
ARG QODANA_UID=1000
ARG QODANA_GID=1000
FROM ${QD_BASE_IMAGE} AS base
ARG QD_DIST=/opt/idea

# The dhi.io hardened base defaults to USER 65532 (nonroot), so apt + user/dir creation below would
# hit "Permission denied" on /var/lib/apt. Switch to root for image construction; runtime.dockerfile
# drops back to the unprivileged qodana user (QODANA_UID, default 1000) for scan-time, so the shipped image is non-root.
# Use the NUMERIC uid 0 — the hardened base's /etc/passwd has no `root` name entry (only `nonroot`),
# so `USER root` fails with "unable to find user root".
USER 0

# OS glue: git for VCS-aware scans; curl for runtime fetches; gzip so locale-gen can read glibc's
# gzip-compressed charmaps (the DHI node base omits gzip, so locale-gen silently produces a broken
# locale without it); jq for entrypoint JSON wrangling; ca-certificates + locales for TLS and UTF-8.
# NO gnupg — signature verification happens only in the dist-builder stage (which apt-installs gnupg
# there); nothing at scan-time needs gpg.
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	# The dhi.io/php:8.4-dev base ships a pre-broken apt state: its cross-gcc-14 has an unsatisfiable
	# `Depends: binutils (>= 2.39)` (only the unversioned-Providing binutils-latest libs are installed),
	# which poisons the solver so even jq/locales below fail to plan. `--fix-broken install` drops the
	# broken gcc-14 chain (the php image needs no C compiler) and repairs the state. Gated on `apt-get
	# check` so the healthy bases (debian-base/golang/node) skip it and stay byte-equivalent.
	if ! apt-get check; then
		apt-get --fix-broken install -y --no-install-recommends
	fi
	apt-get install -y --no-install-recommends \
		ca-certificates \
		curl \
		git \
		gzip \
		jq \
		locales
	sed -i 's/^# *\(en_US.UTF-8\)/\1/' /etc/locale.gen
	locale-gen en_US.UTF-8
	rm -rf /var/lib/apt/lists/*
EOT

ENV LANG=en_US.UTF-8 \
	LANGUAGE=en_US:en \
	LC_ALL=en_US.UTF-8

# qodana user + writable dirs. Bare ARG re-declarations inherit the GLOBAL pre-FROM default (1000) or the
# INCLUDE_ARGS override (js → 1001); a `=1000` default here would SHADOW the override (cli.dockerfile trap).
ARG QODANA_UID
ARG QODANA_GID
RUN <<-EOT
	set -eux
	# Some DHI language bases (e.g. node) do not ship the shadow utilities (useradd/groupadd, both from
	# the `passwd` package); install it ONLY when useradd is absent so the existing debian-base images'
	# apt set stays byte-identical. The `if ! command -v` in a condition cannot trip `set -e`.
	if ! command -v useradd >/dev/null 2>&1; then
		export DEBIAN_FRONTEND=noninteractive
		apt-get update
		apt-get install -y --no-install-recommends passwd
		rm -rf /var/lib/apt/lists/*
	fi
	groupadd --gid "${QODANA_GID}" qodana
	useradd --uid "${QODANA_UID}" --gid "${QODANA_GID}" --create-home --shell /bin/bash qodana
	mkdir -p /data/project /data/cache /data/results "${QD_DIST}"
	chown -R "${QODANA_UID}:${QODANA_GID}" /data "${QD_DIST}"
EOT

# QODANA_* runtime contract (paths the inner CLI reads).
ENV QODANA_DIST=${QD_DIST} \
	QODANA_CONF=/data/project/qodana.yaml \
	QODANA_DOCKER=true \
	QODANA_ENV=cli

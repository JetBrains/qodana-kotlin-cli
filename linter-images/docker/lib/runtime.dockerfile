# runtime — tini PID 1, drop to qodana user, entrypoint. Terminal include.
# Consumes: CLI_BINARY. tini (version + per-arch sha) is pinned HERE, identical across all images.
ARG CLI_BINARY=qodana
ARG TINI_VERSION=v0.19.0
ARG TINI_SHA256_AMD64=93dcc18adc78c65a028a84799ecf8ad40c936fdfc5f2a57b1acda5a8117fa82c
ARG TINI_SHA256_ARM64=07952557df20bfd2a95f9bef198b445e006171969499a1d361bd9e6f8e5e0e81

FROM cli-installed AS runtime
ARG CLI_BINARY
ARG TARGETARCH
ARG TINI_VERSION
ARG TINI_SHA256_AMD64
ARG TINI_SHA256_ARM64
# Bare (no default): inherit the global QODANA_UID/GID declared in base.dockerfile (the language-base
# override). A `=1000` default here would SHADOW the INCLUDE_ARGS override (cli.dockerfile trap).
ARG QODANA_UID
ARG QODANA_GID

# tini PID 1, per-arch. A declarative `COPY --from=tini-${TARGETARCH}` is NOT viable here — the
# dockerfile-x frontend does not expand the ${TARGETARCH} stage variable (parses it as a literal image
# ref). So fetch in a RUN that selects tini-$TARGETARCH + its sha and verifies FAIL-CLOSED: a sha
# mismatch makes `sha256sum -c` exit non-zero and `set -e` aborts the build. curl + sha256sum are present
# (base installs curl; coreutils ships sha256sum); runs as root (base set USER 0; the drop to the qodana
# user is below). Then bake CLI_BINARY into a stable launcher path so the exec-form ENTRYPOINT names a
# concrete binary (JSON arrays do not interpolate ARGs).
# hadolint ignore=DL4006
RUN <<-EOT
	set -eux
	case "${TARGETARCH}" in
		amd64) tini_sha="${TINI_SHA256_AMD64}" ;;
		arm64) tini_sha="${TINI_SHA256_ARM64}" ;;
		*) echo "unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1 ;;
	esac
	curl -fsSL -o /usr/local/bin/tini \
		"https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-${TARGETARCH}"
	echo "${tini_sha}  /usr/local/bin/tini" | sha256sum -c -
	chmod 0755 /usr/local/bin/tini
	ln -sf "/usr/local/bin/${CLI_BINARY}" /usr/local/bin/qodana-entrypoint
EOT

USER ${QODANA_UID}:${QODANA_GID}
WORKDIR /data/project
# tini PID 1 execs the inner CLI and FORWARDS all container args ($@ via CMD) to it. NO baked `scan`
# subcommand — callers always pass `scan …` explicitly (smoke/dogfood `docker run <slug>:dev scan …`).
ENTRYPOINT ["/usr/local/bin/tini", "--", "/usr/local/bin/qodana-entrypoint"]
CMD []

# runtime — tini PID 1, drop to qodana user, entrypoint. Terminal include.
# Consumes: CLI_BINARY. tini (version + per-arch sha) is pinned HERE, identical across all images.
ARG CLI_BINARY=qodana
ARG TINI_VERSION=v0.19.0

# Per-arch tini, declaratively sha-verified by BuildKit (ADD --checksum, fail-closed; DL3020 ADD-url
# stays waived in .hadolint.yaml). COPY --from=tini-${TARGETARCH} below selects one; BuildKit builds
# ONLY the referenced stage. tini-amd64 keeps the historical amd64 sha (byte-identical to the old fetch).
FROM scratch AS tini-amd64
ARG TINI_VERSION
ADD --checksum=sha256:93dcc18adc78c65a028a84799ecf8ad40c936fdfc5f2a57b1acda5a8117fa82c \
	https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-amd64 /tini
FROM scratch AS tini-arm64
ARG TINI_VERSION
ADD --checksum=sha256:07952557df20bfd2a95f9bef198b445e006171969499a1d361bd9e6f8e5e0e81 \
	https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-arm64 /tini

FROM cli-installed AS runtime
ARG CLI_BINARY
ARG TARGETARCH
# Bare (no default): inherit the global QODANA_UID/GID declared in base.dockerfile (the language-base
# override). A `=1000` default here would SHADOW the INCLUDE_ARGS override (cli.dockerfile trap).
ARG QODANA_UID
ARG QODANA_GID

# Select the arch-matching tini (BuildKit builds only the referenced tini-* stage). DL3022: hadolint
# can't resolve the ${TARGETARCH} stage variable, but the tini-amd64/tini-arm64 aliases ARE defined above.
# hadolint ignore=DL3022
COPY --from=tini-${TARGETARCH} /tini /usr/local/bin/tini
# chmod tini + bake CLI_BINARY into a stable launcher path so the exec-form ENTRYPOINT names a
# concrete binary (JSON arrays do not interpolate ARGs). The launcher IS /usr/local/bin/${CLI_BINARY}
# (qodana for jvm/android, qodana-clang for clang) symlinked to a fixed name.
RUN <<-EOT
	set -eux
	chmod 0755 /usr/local/bin/tini
	ln -sf "/usr/local/bin/${CLI_BINARY}" /usr/local/bin/qodana-entrypoint
EOT

USER ${QODANA_UID}:${QODANA_GID}
WORKDIR /data/project
# tini PID 1 execs the inner CLI and FORWARDS all container args ($@ via CMD) to it. NO baked `scan`
# subcommand — callers always pass `scan …` explicitly (smoke/dogfood `docker run <slug>:dev scan …`).
ENTRYPOINT ["/usr/local/bin/tini", "--", "/usr/local/bin/qodana-entrypoint"]
CMD []

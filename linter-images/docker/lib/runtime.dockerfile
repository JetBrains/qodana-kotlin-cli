# runtime — tini PID 1, drop to qodana user, entrypoint. Terminal include.
# Consumes: TINI_VERSION TINI_ARCH TINI_SHA256 CLI_BINARY
ARG TINI_VERSION
ARG TINI_ARCH
ARG TINI_SHA256
ARG CLI_BINARY=qodana

FROM cli-installed AS runtime
ARG TINI_VERSION
ARG TINI_ARCH
ARG TINI_SHA256
ARG CLI_BINARY

# tini as PID 1: declarative sha-verified remote fetch (DL3020 is waived in .hadolint.yaml).
# Arch-parameterized: tini-${TINI_ARCH} with the matching TINI_SHA256 — never hard-wired tini-amd64.
ADD --checksum=sha256:${TINI_SHA256} \
	https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-${TINI_ARCH} \
	/usr/local/bin/tini
# chmod tini + bake CLI_BINARY into a stable launcher path so the exec-form ENTRYPOINT names a
# concrete binary (JSON arrays do not interpolate ARGs). The launcher IS /usr/local/bin/${CLI_BINARY}
# (qodana for jvm/android, qodana-clang for clang) symlinked to a fixed name.
RUN <<-EOT
	set -eux
	chmod 0755 /usr/local/bin/tini
	ln -sf "/usr/local/bin/${CLI_BINARY}" /usr/local/bin/qodana-entrypoint
EOT

USER 1000:1000
WORKDIR /data/project
# tini PID 1 execs the inner CLI and FORWARDS all container args ($@ via CMD) to it. NO baked `scan`
# subcommand — callers always pass `scan …` explicitly (smoke/dogfood `docker run <slug>:dev scan …`).
ENTRYPOINT ["/usr/local/bin/tini", "--", "/usr/local/bin/qodana-entrypoint"]
CMD []

# resharper-clt — install the ReSharper Command Line Tools (CLT/InspectCode) to /opt/qodana-cdnet,
# on PATH. The cdnet aux tool (analogous to clang-tidy for clang). Fetched from the PRIVATE
# qodana-cli-deps mirror (token-gated; CI supplies QODANA_CLI_DEPS_TOKEN), sha256-verified
# fail-closed. Installed under /opt (NOT /data/cache, which is a runtime cache): the qodana-cdnet CLI
# discovers `inspectcode` by name on PATH. The mirror path is ${CLT_MIRROR}/v${CLT_VERSION}/clt.zip;
# the archive is architecture-independent (managed .NET). The secret id (qodana_cli_deps_token) is
# shared with clang and wired in compose.private.yaml + CI.
# Consumes: CLT_MIRROR CLT_VERSION CLT_SHA256
ARG CLT_MIRROR
ARG CLT_VERSION
# sha256 of clt.zip for the pinned CLT_VERSION (arch-independent). Computed from the live mirror
# artifact (cdnet/v2026.1.3/clt.zip, 188,994,101 bytes; content is identical after the resharper-clt
# rename). Not an .env key; overridable per build. Re-verify on a CLT_VERSION bump (see Renovate note).
ARG CLT_SHA256=35d8ada966411cdecdc883a8d5bfacde41b7c8b147aaac85a7140da10f489658
ARG CLT_HOME=/opt/qodana-cdnet

FROM privileged AS tools
ARG CLT_MIRROR
ARG CLT_VERSION
ARG CLT_SHA256
ARG CLT_HOME

# hadolint ignore=DL4006
RUN --mount=type=secret,id=qodana_cli_deps_token,required=true <<-EOT
	set -eu
	: "${CLT_SHA256:?CLT_SHA256 must be set to verify clt.zip}"
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends unzip
	# Read the token + run the authenticated curl with xtrace OFF so the Bearer header never reaches
	# the build log. The BuildKit secret mount keeps it out of any image layer.
	set +x
	TOKEN="$(cat /run/secrets/qodana_cli_deps_token)"
	curl -fsSL -H "Authorization: Bearer ${TOKEN}" \
		-o /tmp/clt.zip \
		"${CLT_MIRROR}/v${CLT_VERSION}/clt.zip"
	unset TOKEN
	set -x
	echo "${CLT_SHA256}  /tmp/clt.zip" | sha256sum -c -
	mkdir -p "${CLT_HOME}/clt" "${CLT_HOME}/bin"
	unzip -q /tmp/clt.zip -d "${CLT_HOME}/clt"
	rm -f /tmp/clt.zip
	# Locate the managed inspectcode entrypoint (TFM-agnostic — survives a CLT bump that changes net8.0).
	# Fail loudly on zero OR multiple matches: a relayout shipping several TFM dirs must not silently
	# pick one.
	EXE="$(find "${CLT_HOME}/clt" -path '*/tools/*/any/inspectcode.exe')"
	n="$(printf '%s\n' "$EXE" | grep -c .)"
	[ "$n" -eq 1 ] || { echo "CLT layout: expected exactly one inspectcode.exe under ${CLT_HOME}/clt, found $n:"; printf '%s\n' "$EXE"; exit 1; }
	D="$(dirname "$EXE")"
	[ -f "$D/inspectcode.unix.runtimeconfig.json" ] || { echo "CLT: missing unix runtimeconfig at $D"; exit 1; }
	# Thin launcher (NOT a symlink: the shipped inspectcode.sh resolves siblings via dirname $0).
	# Use printf (NOT a nested heredoc) to dodge tab-fidelity fragility through dockerfile-x + BuildKit:
	# $D is baked as the resolved absolute path via %s; the literal "$@" stays in the generated script.
	printf '#!/bin/sh\nexec dotnet exec --runtimeconfig "%s/inspectcode.unix.runtimeconfig.json" "%s/inspectcode.exe" "$@"\n' "$D" "$D" > "${CLT_HOME}/bin/inspectcode"
	chmod 0755 "${CLT_HOME}/bin/inspectcode"
	# Match the sibling toolchains (clang ${CLANG_TIDY_TOOLS_DIR}, android /opt/android-sdk + /opt/java):
	# the runtime drops to USER 1000, so the installed tree must be owned by uid 1000 lest a per-tool
	# InspectCode write fail. The .NET SDK under /usr/share/dotnet stays root-owned/read-only by design
	# (restore + tooling write to $HOME, set up for the qodana user by base.dockerfile).
	chown -R 1000:1000 "${CLT_HOME}"
	# Build-time validation: the two existence checks above already prove the baked launcher points at
	# real files, so a bad/relayout'd archive fails the BUILD, not a scan.
	apt-get purge -y unzip
	apt-get autoremove -y
	rm -rf /var/lib/apt/lists/*
EOT

ENV PATH="${CLT_HOME}/bin:${PATH}"

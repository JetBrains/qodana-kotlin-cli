# toolchain/dotnet — .NET SDKs (8/9/10) via a pinned dotnet-install.sh + .NET runtime libs. cdnet-only.
# Layers onto `base` (USER 0). InspectCode builds the solution, so it needs the SDK, not just the runtime.
# Pins live as ARGs here (NOT .env keys), mirroring qodana-cli's dotnet-community base. Renovate tracks
# the install-script revision/sha via the customManager added in this change.
ARG DOTNET_INSTALL_SH_REVISION=2e497bbe880cf47b209fe0d1f9c5e051916f830e
ARG DOTNET_INSTALL_SH_SHA256=3f30fbfa69e182be7e60fd0cd9189c53cb61799b6077159fec74341112f1715e
ARG DOTNET_CHANNELS="8.0 9.0 10.0"
ARG DOTNET_ROOT=/usr/share/dotnet

FROM base AS dotnet-toolchain
ARG DOTNET_INSTALL_SH_REVISION
ARG DOTNET_INSTALL_SH_SHA256
ARG DOTNET_CHANNELS
ARG DOTNET_ROOT

ENV DOTNET_ROOT=${DOTNET_ROOT}
ENV PATH="${DOTNET_ROOT}:${DOTNET_ROOT}/tools:${PATH}"

# .NET on the hardened bookworm base needs these shared libs (ICU/SSL/krb5/etc.).
# hadolint ignore=DL4006
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends \
		libc6 libgcc-s1 libgssapi-krb5-2 libicu72 libssl3 libstdc++6 zlib1g tzdata
	curl -fsSL \
		"https://raw.githubusercontent.com/dotnet/install-scripts/${DOTNET_INSTALL_SH_REVISION}/src/dotnet-install.sh" \
		-o /tmp/dotnet-install.sh
	echo "${DOTNET_INSTALL_SH_SHA256}  /tmp/dotnet-install.sh" | sha256sum -c -
	chmod +x /tmp/dotnet-install.sh
	for ch in ${DOTNET_CHANNELS}; do
		/tmp/dotnet-install.sh --channel "$ch" --install-dir "${DOTNET_ROOT}"
	done
	installed="$(dotnet --list-sdks)"
	for ch in ${DOTNET_CHANNELS}; do
		echo "$installed" | grep -Eq "^$ch" || { echo "missing SDK channel $ch"; echo "$installed"; exit 1; }
	done
	rm -f /tmp/dotnet-install.sh
	rm -rf /var/lib/apt/lists/*
EOT

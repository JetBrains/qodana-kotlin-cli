# toolchain/dotnet — .NET SDKs (8/9/10) via a pinned dotnet-install.sh + .NET runtime libs.
# SHARED by qodana-cdnet (Community, bookworm, dist-less) and qodana-dotnet (Ultimate Rider, trixie, dist).
# Layers onto `base` (USER 0). InspectCode/Rider build the solution, so they need the SDK, not just the runtime.
# Pins live as ARGs here (NOT .env keys), mirroring qodana-cli's dotnet-community base.
# The install-script revision+sha are pinned and verified fail-closed at build (sha256sum -c). They are
# manually maintained (no Renovate manager) — re-verify the sha when bumping the revision.
ARG DOTNET_INSTALL_SH_REVISION=2e497bbe880cf47b209fe0d1f9c5e051916f830e
ARG DOTNET_INSTALL_SH_SHA256=3f30fbfa69e182be7e60fd0cd9189c53cb61799b6077159fec74341112f1715e
ARG DOTNET_CHANNELS="8.0 9.0 10.0"
ARG DOTNET_ROOT=/usr/share/dotnet

FROM base AS dotnet-toolchain
ARG DOTNET_INSTALL_SH_REVISION
ARG DOTNET_INSTALL_SH_SHA256
ARG DOTNET_CHANNELS
ARG DOTNET_ROOT
# libicu major is tied to the base distro generation: cdnet's bookworm base ships libicu72, qodana-dotnet's
# trixie base ships libicu76. Parameterized via the `.env`-keyed LIBICU_PKG. This ARG is BARE (no default)
# on purpose: dockerfile-x emits each INCLUDE_ARGS `.env` key as a global `ARG NAME="val"` at the top of the
# resolved file, and a same-name `ARG NAME=default` declared LATER (here) would CLOBBER it back (the
# QODANA_UID trap). So inherit the INCLUDE_ARGS global and supply cdnet's default at the use site below via
# `${LIBICU_PKG:-libicu72}` — cdnet sets no key (its `.env` is unchanged) so it stays on libicu72; dotnet's
# `.env` sets LIBICU_PKG=libicu76.
ARG LIBICU_PKG

ENV DOTNET_ROOT=${DOTNET_ROOT}
ENV PATH="${DOTNET_ROOT}:${DOTNET_ROOT}/tools:${PATH}"

# .NET on the hardened base needs these shared libs (ICU/SSL/krb5/etc.). ICU's major tracks the distro
# generation, so it is parameterized: ${LIBICU_PKG:-libicu72} (cdnet bookworm → libicu72, dotnet trixie → libicu76).
# hadolint ignore=DL4006
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends \
		libc6 libgcc-s1 libgssapi-krb5-2 "${LIBICU_PKG:-libicu72}" libssl3 libstdc++6 zlib1g tzdata
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
		echo "$installed" | grep -Eq "^${ch}\." || { echo "missing SDK channel $ch"; echo "$installed"; exit 1; }
	done
	rm -f /tmp/dotnet-install.sh
	rm -rf /var/lib/apt/lists/*
EOT

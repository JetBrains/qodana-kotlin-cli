# toolchain/clang — clang/clang++/cmake from the LLVM apt repo, pinned by CLANG major.
# Base-OS split (bookworm 16-19 / trixie 20-21) arrives as CLANG_OS from the matrix row.
# Consumes: CLANG CLANG_OS.  Layers onto `base`.
ARG CLANG
ARG CLANG_OS

FROM base AS clang-toolchain
ARG CLANG
ARG CLANG_OS

# LLVM apt repo for the pinned major on the matching Debian codename, plus cmake/make.
# hadolint ignore=DL4006
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	# gnupg is not in the hardened base; install it for gpg --dearmor and purge it in this same layer.
	apt-get update
	apt-get install -y --no-install-recommends gnupg
	mkdir -p /etc/apt/keyrings
	curl -fsSL https://apt.llvm.org/llvm-snapshot.gpg.key \
		| gpg --dearmor -o /etc/apt/keyrings/llvm.gpg
	echo "deb [signed-by=/etc/apt/keyrings/llvm.gpg] http://apt.llvm.org/${CLANG_OS}/ llvm-toolchain-${CLANG_OS}-${CLANG} main" \
		> /etc/apt/sources.list.d/llvm.list
	apt-get update
	# clang-${CLANG} pulls libobjc-<gcc>-dev, which `=`-pins the STOCK Debian gcc runtime (e.g. gcc-14-base
	# = 14.2.0-19). The dhi.io trixie debian-base bakes that runtime in at the vendor-patched +dhi revision
	# and ships no +dhi build of libobjc-<gcc>-dev, so apt deadlocks ("held broken packages") and clang never
	# installs (cpp, CLANG=20). On failure, pin the gcc runtime family back to the stock Debian origin and
	# retry: apt then DOWNGRADES the whole tree coherently off +dhi so libobjc-<gcc>-dev's `=` pins resolve.
	# Gated on the first install FAILING, so bookworm (qodana-clang, CLANG=19 — the LLVM bookworm repo's
	# libobjc-12-dev matches the base's stock gcc-12) succeeds first-try, never writes the pin, and stays
	# byte-equivalent. The pin is build-time only — removed below so the shipped image keeps the vendor apt
	# state. `o=Debian` is the stock archive's origin; `+dhi` packages come from dhi.io with a distinct origin.
	clang_pkgs="clang-${CLANG} clang-tools-${CLANG} cmake make"
	# shellcheck disable=SC2086
	if ! apt-get install -y --no-install-recommends ${clang_pkgs}; then
		cat > /etc/apt/preferences.d/gcc-stock <<-'PREF'
			Package: gcc-*-base libgcc-* libstdc++* libgomp* libitm* libatomic* libasan* liblsan* libtsan* libubsan* libhwasan* libquadmath* libcc1-* libobjc*
			Pin: release o=Debian
			Pin-Priority: 1001
		PREF
		apt-get install -y --no-install-recommends --allow-downgrades ${clang_pkgs}
		rm -f /etc/apt/preferences.d/gcc-stock
	fi
	update-alternatives --install /usr/bin/clang clang "/usr/bin/clang-${CLANG}" 100
	update-alternatives --install /usr/bin/clang++ clang++ "/usr/bin/clang++-${CLANG}" 100
	apt-get purge -y gnupg
	apt-get autoremove -y
	rm -rf /var/lib/apt/lists/*
EOT

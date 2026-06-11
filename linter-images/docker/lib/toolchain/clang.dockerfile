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
	mkdir -p /etc/apt/keyrings
	curl -fsSL https://apt.llvm.org/llvm-snapshot.gpg.key \
		| gpg --dearmor -o /etc/apt/keyrings/llvm.gpg
	echo "deb [signed-by=/etc/apt/keyrings/llvm.gpg] http://apt.llvm.org/${CLANG_OS}/ llvm-toolchain-${CLANG_OS}-${CLANG} main" \
		> /etc/apt/sources.list.d/llvm.list
	apt-get update
	apt-get install -y --no-install-recommends \
		"clang-${CLANG}" \
		"clang-tools-${CLANG}" \
		cmake \
		make
	update-alternatives --install /usr/bin/clang clang "/usr/bin/clang-${CLANG}" 100
	update-alternatives --install /usr/bin/clang++ clang++ "/usr/bin/clang++-${CLANG}" 100
	rm -rf /var/lib/apt/lists/*
EOT

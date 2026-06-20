# syntax=docker.io/devthefuture/dockerfile-x:1.6.0@sha256:000d1ae882609bf9a7a3aa4647370d55ffb769580ea5895987192996ffed159f
INCLUDE_ARGS images/qodana-cpp.env
INCLUDE lib/base.dockerfile
INCLUDE lib/toolchain/clang.dockerfile
INCLUDE lib/toolchain/node.dockerfile
INCLUDE lib/toolchain/eslint.dockerfile
INCLUDE lib/privileged.dockerfile
INCLUDE lib/dist.dockerfile
INCLUDE lib/cli.dockerfile
INCLUDE lib/runtime.dockerfile

# Everything CLion (cpp) needs at scan time that the hardened trixie base does not provide. cpp-local (NOT
# in lib/toolchain/clang.dockerfile) so qodana-clang stays byte-unchanged. Bare ARGs inherit the
# INCLUDE_ARGS / base.dockerfile globals (a `=20`/`=1000` default here would shadow them — the QODANA_UID
# trap). The runtime stage has already dropped to the qodana user, so switch to root, then drop back so the
# shipped image still scans unprivileged.
#
# (1) Native libs for two CLion subsystems:
#   - CLion's analysis backend is a .NET "Rider host"/ReSharper process — without the .NET runtime libs
#     (ICU/SSL/krb5/…) it aborts at startup with exit 134 (SIGABRT, the classic .NET "no valid ICU").
#   - The bundled JBR's font manager dlopens libfreetype.so.6 the moment CLion creates the CMake
#     output-console editor during project-model generation; absent → UnsatisfiedLinkError.
#   Either failure wedges the headless analyzer at "Awaits CLion backend activities" — it HANGS instead of
#   failing (QD-15107). The source qodana-cli cpp base pulls these in via `default-jre` (fonts) + its
#   dotnet-community sibling (the .NET runtime libs). libicu major tracks the distro generation — cpp is
#   trixie → libicu76 (matching lib/toolchain/dotnet.dockerfile).
# (2) A self-pinned C/C++ compiler. CLion's bundled CMake reads CXX/CC and FATALs if the path fails its
#   EXISTS check (→ "CMake configuration failed", a late scan hang/fail). The LLVM package's
#   /usr/lib/llvm-${CLANG}/bin/clang++ symlink is unreliable on the dhi base after clang.dockerfile's
#   gcc-pin --allow-downgrades fallback, so pin our own /usr/local/bin/clang{,++} → the real clang driver
#   (clang acts as the C++ driver when invoked via a `++` name) and run `clang++ --version` so a
#   missing/broken compiler fails the BUILD (loud) rather than the scan.
ARG CLANG
ARG QODANA_UID
ARG QODANA_GID
USER 0
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends \
		fontconfig libfreetype6 \
		libc6 libgcc-s1 libgssapi-krb5-2 libicu76 libssl3 libstdc++6 zlib1g tzdata
	rm -rf /var/lib/apt/lists/*
	# Resolve the REAL clang driver (follow clang.dockerfile's update-alternatives chain, which bypasses the
	# unreliable clang++ symlink) and pin both names to it; clang acts as the C++ driver via the `++` name.
	clang_real="$(readlink -f "$(command -v "clang-${CLANG}")")"
	test -x "${clang_real}"
	ln -sf "${clang_real}" /usr/local/bin/clang
	ln -sf "${clang_real}" /usr/local/bin/clang++
	/usr/local/bin/clang --version
	/usr/local/bin/clang++ --version
EOT
ENV CXX="/usr/local/bin/clang++" \
	CC="/usr/local/bin/clang" \
	CPLUS_INCLUDE_PATH="/usr/lib/clang/${CLANG}/include"
USER ${QODANA_UID}:${QODANA_GID}

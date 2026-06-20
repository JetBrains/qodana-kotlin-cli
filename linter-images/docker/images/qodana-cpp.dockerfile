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

# CLion's C/C++ analysis resolves the compiler for compile_commands.json TUs from CXX/CC, so the source
# cpp.Dockerfile exports these pointing at the pinned LLVM install (clang.dockerfile installs it under
# /usr/lib/llvm-${CLANG}). cpp-local (NOT in lib/toolchain/clang.dockerfile) so qodana-clang stays
# byte-unchanged. Bare `ARG CLANG` inherits the INCLUDE_ARGS global value (a `=20` default here would
# shadow it — the base.dockerfile QODANA_UID trap); these append onto the final `runtime` stage.
ARG CLANG
ENV CXX="/usr/lib/llvm-${CLANG}/bin/clang++" \
	CC="/usr/lib/llvm-${CLANG}/bin/clang" \
	CPLUS_INCLUDE_PATH="/usr/lib/clang/${CLANG}/include"

# Native libs the CLion dist loads at scan time but the hardened trixie base omits. Two subsystems:
#  - CLion's analysis backend is a .NET "Rider host"/ReSharper process — without the .NET runtime libs
#    (ICU/SSL/krb5/…) it aborts at startup with exit 134 (SIGABRT, the classic .NET "no valid ICU").
#  - The bundled JBR's font manager dlopens libfreetype.so.6 the moment CLion creates the CMake
#    output-console editor during project-model generation; absent → UnsatisfiedLinkError.
# Either failure wedges the headless analyzer at "Awaits CLion backend activities" — it HANGS instead of
# failing (QD-15107). The source qodana-cli cpp base pulls these in via `default-jre` (fonts) + its
# dotnet-community sibling (the .NET runtime libs); install the same set here, scoped to cpp (qodana-clang
# is native-image and needs none of it, so this stays out of lib/toolchain/clang.dockerfile). libicu major
# tracks the distro generation — cpp is trixie → libicu76 (matching lib/toolchain/dotnet.dockerfile). The
# runtime stage has already dropped to the qodana user, so switch to root for apt, then drop back so the
# shipped image still scans unprivileged. Bare ARGs inherit the global QODANA_UID/GID (base.dockerfile).
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
EOT
USER ${QODANA_UID}:${QODANA_GID}

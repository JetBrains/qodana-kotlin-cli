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

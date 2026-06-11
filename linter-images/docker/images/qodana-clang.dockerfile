# syntax=docker.io/devthefuture/dockerfile-x:1.6.0@sha256:000d1ae882609bf9a7a3aa4647370d55ffb769580ea5895987192996ffed159f
INCLUDE_ARGS images/qodana-clang.env
INCLUDE lib/base.dockerfile
INCLUDE lib/toolchain/clang.dockerfile
INCLUDE lib/privileged.dockerfile
INCLUDE lib/tools.dockerfile
INCLUDE lib/cli.dockerfile
INCLUDE lib/runtime.dockerfile

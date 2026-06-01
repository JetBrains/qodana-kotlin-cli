#!/usr/bin/env bash
set -euo pipefail
source ~/work/qd-14840/env.sh
echo "JAVA_HOME=$JAVA_HOME"
echo "PATH=$PATH" | tr ':' '\n' | head -10
cd ~/work/qd-14840/graal/substratevm
echo "--- mx build (JAVA_HOME from env.sh) start ---"
date
mx build 2>&1
RC=$?
echo "--- mx build done with code $RC ---"
date
exit $RC

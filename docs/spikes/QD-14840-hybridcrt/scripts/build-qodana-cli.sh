#!/usr/bin/env bash
set -uo pipefail
source ~/work/qd-14840/env.sh
PATCHED_GRAAL=~/work/qd-14840/graal/sdk/mxbuild/windows-amd64/GRAALVM_9DA8311EB5_JAVA25/graalvm-9da8311eb5-java25-25.0.3
export GRAALVM_HOME="$PATCHED_GRAAL"
export JAVA_HOME="$PATCHED_GRAAL"
echo "GRAALVM_HOME=$GRAALVM_HOME"
echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -2
cd ~/work/qd-14840/qodana-kotlin-cli
./gradlew --no-daemon :qodana-cli:nativeCompile \
  -Dorg.gradle.java.installations.paths="$PATCHED_GRAAL" \
  -Dorg.gradle.java.installations.auto-download=false 2>&1
RC=$?
echo "--- gradle exit code: $RC ---"
exit $RC

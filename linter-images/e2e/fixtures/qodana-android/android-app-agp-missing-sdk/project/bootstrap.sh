#!/usr/bin/env bash
# NEGATIVE TWIN bootstrap: registers a complete JDK for the Gradle daemon (so the failure is
# unambiguously "no compile SDK", not "no JDK"), but DELIBERATELY does NOT install
# platforms;android-34 / build-tools;34.0.0. The AGP import then fails and the scan exits non-zero
# with a missing-platform message (QD-2179, QD-15022).
set -euxo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must be exported by the qodana-android image}"

# Give the Gradle daemon a complete JDK (the QD-14924 pattern) so the only missing piece is the
# compile SDK — that is what this negative case is meant to surface.
corretto17=/opt/java/corretto17/java-17-amazon-corretto
[[ -x $corretto17/bin/java ]] || { echo "missing-sdk bootstrap: Corretto 17 not found at $corretto17" >&2; exit 1; }
gradle_home="${GRADLE_USER_HOME:-${HOME:-}/.gradle}"
mkdir -p "$gradle_home"
props="$gradle_home/gradle.properties"
touch "$props"
if ! grep -qF "$corretto17" "$props" 2>/dev/null; then
  echo "org.gradle.java.installations.paths=$corretto17" >> "$props"
fi

# NOTE: no `sdkmanager "platforms;android-34" "build-tools;34.0.0"` here — that omission is the point.

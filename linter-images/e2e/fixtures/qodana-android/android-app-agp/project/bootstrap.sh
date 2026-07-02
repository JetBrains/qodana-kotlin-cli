#!/usr/bin/env bash
# Accept SDK licenses and install the compile SDK + build-tools the app needs, then register a
# complete JDK for the Gradle daemon. Runs inside the qodana-android linter container, where
# ANDROID_HOME=/opt/android-sdk and Corretto 11/17 live under /opt/java (QD-15022, QD-14924).
set -euxo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must be exported by the qodana-android image}"

sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[[ -x $sdkmanager ]] || { echo "android-app-agp bootstrap: sdkmanager not found at $sdkmanager" >&2; exit 1; }

# sdkmanager is a Java tool; the bundled Corretto 17 runs it (the SDK install path also needs a JDK).
# Use the real JDK dir — the java/jre symlinks dangle in the layered image.
corretto17=/opt/java/corretto17/java-17-amazon-corretto
[[ -x $corretto17/bin/java ]] || { echo "android-app-agp bootstrap: Corretto 17 not found at $corretto17" >&2; exit 1; }
export JAVA_HOME="$corretto17"
export PATH="$JAVA_HOME/bin:$PATH"

# Accept all licenses, then install the compile SDK (android.jar for compileSdk 34). NOT build-tools:
# a Qodana scan is an IDE Gradle model import + inspections, never a `./gradlew` build, so it never
# executes the SDK's native resource compiler (aapt2). Installing build-tools would provision an
# arch-specific binary the scan never runs — dead weight on amd64 and unrunnable x86_64 on arm64 —
# so it's omitted (arch-neutral). `platforms;android-34` is pure android.jar (Java, arch-neutral).
# `|| true`: `yes` is killed by SIGPIPE (exit 141) when sdkmanager stops reading after the last
# prompt, which `set -o pipefail` would otherwise turn into a bootstrap failure — the image's own
# android.dockerfile guards the identical `yes | sdkmanager --licenses` the same way. A genuine
# license problem still surfaces loudly at the platform install below (no `|| true` there).
yes | "$sdkmanager" --licenses > /dev/null || true
"$sdkmanager" "platforms;android-34"

# Give the Gradle daemon a complete JDK: AGP 8.5 needs JDK 17; the bundled JBR is a runtime, not a
# full JDK, so Gradle rejects it for the daemon-JVM criteria. Register Corretto 17 in the writable
# Gradle user home (rootless container; /opt/java is read-only but readable). (QD-14924 pattern.)
gradle_home="${GRADLE_USER_HOME:-${HOME:-}/.gradle}"
mkdir -p "$gradle_home"
props="$gradle_home/gradle.properties"
touch "$props"
if ! grep -qF "$corretto17" "$props" 2>/dev/null; then
  echo "org.gradle.java.installations.paths=$corretto17" >> "$props"
fi

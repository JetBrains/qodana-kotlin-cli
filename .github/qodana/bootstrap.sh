#!/usr/bin/env bash
# Provision a complete JDK for the Qodana linter's Gradle daemon (QD-14924).
#
# The linter container's bundled JBR is a runtime, not a full JDK (no jar / jdk.jartool), so
# Gradle rejects it for the daemon-JVM criteria in gradle/gradle-daemon-jvm.properties and the
# project import fails with "No defined toolchain download url". The container is rootless
# (/usr/lib/jvm and /opt are read-only), so install a matching complete JDK under the writable
# Gradle home and register it via org.gradle.java.installations.paths, which daemon auto-detection
# reads. The version is read from the criteria file so it tracks future bumps. Runs only in the
# linter container — no effect on native-build or other CI jobs.
set -euxo pipefail

criteria=gradle/gradle-daemon-jvm.properties

# Echo the whitespace-stripped value of properties key $1 in $criteria (empty if absent).
prop() {
  local line
  line=$(grep -m1 -E "^[[:space:]]*$1[[:space:]]*=" "$criteria" || true)
  line=${line#*=}
  printf '%s' "${line//[[:space:]]/}"
}

[[ -f $criteria ]] || { echo "QD-14924 bootstrap: '$criteria' not found (run from the project root)" >&2; exit 1; }

version=$(prop toolchainVersion)
major=${version%%.*}
[[ $major =~ ^[0-9]+$ ]] || { echo "QD-14924 bootstrap: unparseable toolchainVersion '$version'" >&2; exit 1; }

# We provision Eclipse Temurin; bail if the criteria ever pins a vendor it can't satisfy.
vendor=$(prop toolchainVendor)
case "${vendor,,}" in
  '' | adoptium | eclipse* | temurin) ;;
  *) echo "QD-14924 bootstrap: criteria pins vendor '$vendor'; this bootstrap provisions Eclipse Temurin — update qodana.yaml" >&2; exit 1 ;;
esac

# Rootless container: install into the writable Gradle user home, not /usr/lib/jvm or /opt.
gradle_home="${GRADLE_USER_HOME:-${HOME:-}/.gradle}"
mkdir -p "$gradle_home"
[[ -w $gradle_home ]] || { echo "QD-14924 bootstrap: Gradle user home '$gradle_home' is not writable" >&2; exit 1; }
dest="$gradle_home/qd-jvm/temurin-$major"

if [[ -x $dest/bin/javac && -x $dest/bin/jar ]]; then
  echo "QD-14924 bootstrap: JDK $major already provisioned at $dest"
else
  case "$(uname -m)" in
    aarch64 | arm64) arch=aarch64 ;;
    x86_64 | amd64) arch=x64 ;;
    *) echo "QD-14924 bootstrap: unsupported architecture '$(uname -m)'" >&2; exit 1 ;;
  esac
  url="https://api.adoptium.net/v3/binary/latest/$major/ga/linux/$arch/jdk/hotspot/normal/eclipse"
  rm -rf "$dest.new"
  mkdir -p "$dest.new"
  curl -fsSL --retry 5 --retry-all-errors --connect-timeout 10 --max-time 600 -o /tmp/qd-jdk.tgz "$url"
  tar -xzf /tmp/qd-jdk.tgz -C "$dest.new" --strip-components=1
  rm -f /tmp/qd-jdk.tgz
  [[ -x $dest.new/bin/javac && -x $dest.new/bin/jar ]] || { echo "QD-14924 bootstrap: downloaded JDK is incomplete" >&2; exit 1; }
  rm -rf "$dest"
  mv "$dest.new" "$dest"
  echo "QD-14924 bootstrap: provisioned Temurin JDK $major ($arch) at $dest"
fi

# Register the JDK for Gradle daemon-JVM auto-detection (idempotent; preserves other keys).
props="$gradle_home/gradle.properties"
touch "$props"
current=$(grep -m1 -E '^[[:space:]]*org\.gradle\.java\.installations\.paths[[:space:]]*=' "$props" || true)
current=${current#*=}
current=${current//[[:space:]]/}
if [[ ",$current," == *",$dest,"* ]]; then
  echo "QD-14924 bootstrap: installations.paths already includes $dest"
else
  merged="${current:+$current,}$dest"
  { grep -vE '^[[:space:]]*org\.gradle\.java\.installations\.paths[[:space:]]*=' "$props" || true; } > "$props.tmp"
  echo "org.gradle.java.installations.paths=$merged" >> "$props.tmp"
  mv "$props.tmp" "$props"
  echo "QD-14924 bootstrap: registered installations.paths -> $dest"
fi

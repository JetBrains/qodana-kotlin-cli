#!/usr/bin/env bash
# Resolve a dockerfile-x thin image's INCLUDE/INCLUDE_ARGS and hadolint the output.
# Usage: resolve-and-lint.sh <thin-dockerfile-relative-to-docker>
# Runs from docker/ so INCLUDE paths resolve against the context root. Fails loudly
# if npx/dockerfile-x/hadolint are unavailable (no silent skip).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
thin="${1:?usage: resolve-and-lint.sh <thin-dockerfile-relative-to-docker>}"

command -v npx >/dev/null || {
  echo "FATAL: npx not on PATH" >&2
  exit 70
}
command -v hadolint >/dev/null || {
  echo "FATAL: hadolint not on PATH" >&2
  exit 70
}

resolved="$(mktemp -t resolved.XXXXXX.dockerfile)"
trap 'rm -f "$resolved"' EXIT

# dockerfile-x resolves INCLUDE/INCLUDE_ARGS textually against the context root (cwd).
(cd "$here" && npx --yes dockerfile-x@1.6.0 "$thin") >"$resolved"

echo "=== resolved $thin ==="
cat "$resolved"
echo "=== hadolint ($here/.hadolint.yaml) ==="
(cd "$here" && hadolint --config .hadolint.yaml "$resolved")

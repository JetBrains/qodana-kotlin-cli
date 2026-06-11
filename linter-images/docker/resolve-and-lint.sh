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

# dockerfile-x resolves INCLUDE/INCLUDE_ARGS against the context root (cwd). The standalone CLI
# takes the thin file via -f (a bare positional is rejected) and emits the resolved Dockerfile on
# stdout. INCLUDE_ARGS needs the referenced images/<slug>.env to exist, so this only resolves once
# the per-linter .env files land (Phase 4); until then it fails loudly with a missing-file error.
#
# dockerfile-x STRIPS blank lines when concatenating includes, collapsing each `EOT`-terminated
# heredoc directly against the next instruction (e.g. `EOT` then `ENV`). hadolint's parser then
# bails with "unexpected '<C>' expecting a new line followed by the next instruction" (hadolint
# #1137) — a lint-only false positive: BuildKit itself parses this output fine. Re-insert one blank
# line after each lone heredoc terminator so the resolved file lints cleanly without changing what
# `docker build` sees. (`EOT` is the single heredoc word used across every lib/ include.)
(cd "$here" && npx --yes dockerfile-x@1.6.0 -f "$thin") \
  | awk '{ print } /^EOT[[:space:]]*$/ { print "" }' >"$resolved"

echo "=== resolved $thin ==="
cat "$resolved"
echo "=== hadolint ($here/.hadolint.yaml) ==="
(cd "$here" && hadolint --config .hadolint.yaml "$resolved")

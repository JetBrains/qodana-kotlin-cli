#!/usr/bin/env bash
# prek entry: resolve every thin image via dockerfile-x and hadolint the output. Quiet on success;
# on failure prints the offending image + hadolint output and exits non-zero.
#
# Runs locally AND in CI (ci.yaml runs prek). No Docker daemon: dockerfile-x resolution is pure text.
# hadolint comes from the hook's pip env (hadolint-py, pinned in .pre-commit-config.yaml); npx (Node)
# fetches dockerfile-x on demand.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"

command -v npx >/dev/null || { echo "FATAL: npx (Node) not on PATH — needed to resolve dockerfile-x" >&2; exit 70; }
command -v hadolint >/dev/null || { echo "FATAL: hadolint not on PATH (prek provisions it via hadolint-py)" >&2; exit 70; }

fail=0
for thin in images/*.dockerfile; do
  # dockerfile-x strips blank lines when concatenating includes, gluing each `EOT` heredoc terminator
  # onto the next instruction; hadolint then mis-parses it (hadolint #1137, a lint-only false positive —
  # BuildKit parses it fine). Re-insert one blank line after each lone `EOT` before linting.
  if ! resolved="$(npx --yes dockerfile-x@1.6.0 -f "$thin" | awk '{ print } /^EOT[[:space:]]*$/ { print "" }')"; then
    echo "resolve FAILED: $thin" >&2
    fail=1
    continue
  fi
  if ! out="$(printf '%s\n' "$resolved" | hadolint --config .hadolint.yaml - 2>&1)"; then
    echo "hadolint FAILED: $thin" >&2
    printf '%s\n' "$out" >&2
    fail=1
  fi
done
exit "$fail"

#!/usr/bin/env bash
# See action.yaml for the contract. Capping each attempt needs a `timeout`/`gtimeout` binary — present on
# CI; if absent (e.g. local macOS without coreutils), the per-attempt cap is skipped.
set -euxo pipefail
exec 2>&1

: "${RUN:?RUN is required}"
: "${TIMEOUT_SECONDS:?}" "${INITIAL_DELAY_SECONDS:?}" "${MAX_DELAY_SECONDS:?}"
what="${WHAT:-command}"
sleep_cmd="${SLEEP_CMD:-sleep}"   # test seam: ':' exercises backoff math without real waits

# Prefer GNU `timeout` (or `gtimeout` from macOS coreutils) to cap each attempt; skip the cap if neither exists.
timeout_bin=""
if command -v timeout >/dev/null 2>&1; then
    timeout_bin=timeout
elif command -v gtimeout >/dev/null 2>&1; then
    timeout_bin=gtimeout
fi

# Run the wrapped command once, capped at the remaining budget when a timeout binary is available. Reads the
# current `remaining` from the loop; returns the command's real exit code (124 when the cap fires).
run_once() {
    if [[ -n "$timeout_bin" ]]; then
        "$timeout_bin" "$remaining" bash -c "$RUN"
    else
        bash -c "$RUN"
    fi
}

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
delay="$INITIAL_DELAY_SECONDS"
attempt=0

while true; do
    attempt=$(( attempt + 1 ))
    remaining=$(( deadline - $(date +%s) ))
    if [[ "$remaining" -le 0 ]]; then
        echo "::error::$what: ${TIMEOUT_SECONDS}s budget exhausted before attempt $attempt — giving up"
        exit 75
    fi

    # `if run_once` keeps errexit from aborting on the expected non-zero, while still capturing the real code.
    if run_once; then
        rc=0
    else
        rc=$?
    fi
    if [[ "$rc" -eq 0 ]]; then
        exit 0
    fi

    now=$(date +%s)
    if [[ -n "$timeout_bin" && "$rc" -eq 124 && "$now" -ge "$deadline" ]]; then
        echo "::error::$what: killed at the ${TIMEOUT_SECONDS}s deadline (attempt $attempt, no progress — likely hung)"
        exit 124
    fi
    if [[ "$rc" -ne 75 ]]; then
        echo "::error::$what failed (exit $rc) — not a transient signal (75), not retrying"
        exit "$rc"
    fi
    if [[ "$now" -ge "$deadline" ]]; then
        echo "::error::$what still transiently failing after ${TIMEOUT_SECONDS}s (attempt $attempt) — giving up"
        exit 75
    fi

    remaining=$(( deadline - now ))
    if [[ "$delay" -gt "$remaining" ]]; then
        delay="$remaining"
    fi
    echo "::warning::$what transient failure (attempt $attempt); retrying in ${delay}s"
    "$sleep_cmd" "$delay"

    delay=$(( delay * 2 ))
    if [[ "$delay" -gt "$MAX_DELAY_SECONDS" ]]; then
        delay="$MAX_DELAY_SECONDS"
    fi
done

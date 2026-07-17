#!/usr/bin/env bash
# Re-runs $RUN while it exits 75 (EX_TEMPFAIL = "transient, retry me"); 0 succeeds, any other code fails
# fast. The retry loop is bounded by wall-clock TIMEOUT_SECONDS, and each attempt is capped by `timeout`
# so a hung attempt cannot outlive the budget (needs a `timeout`/`gtimeout` binary — present on CI; if
# absent, e.g. local macOS without coreutils, the per-attempt cap is skipped). The command owns the
# retry decision; this script never inspects output.
set -uo pipefail
: "${RUN:?RUN is required}"
: "${TIMEOUT_SECONDS:?}" "${INITIAL_DELAY_SECONDS:?}" "${MAX_DELAY_SECONDS:?}"
what="${WHAT:-command}"
sleep_cmd="${SLEEP_CMD:-sleep}"   # test seam: ':' exercises backoff math without real waits

timeout_bin=""
command -v timeout >/dev/null 2>&1 && timeout_bin=timeout
[ -z "$timeout_bin" ] && command -v gtimeout >/dev/null 2>&1 && timeout_bin=gtimeout

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
delay="$INITIAL_DELAY_SECONDS"
attempt=0
while :; do
    attempt=$(( attempt + 1 ))
    remaining=$(( deadline - $(date +%s) ))
    if [ "$remaining" -le 0 ]; then
        echo "::error::$what: ${TIMEOUT_SECONDS}s budget exhausted before attempt $attempt — giving up"
        exit 75
    fi
    if [ -n "$timeout_bin" ]; then
        "$timeout_bin" "$remaining" bash -c "$RUN"
    else
        bash -c "$RUN"
    fi
    rc=$?
    [ "$rc" -eq 0 ] && exit 0
    if [ -n "$timeout_bin" ] && [ "$rc" -eq 124 ]; then
        echo "::error::$what: killed at the ${TIMEOUT_SECONDS}s deadline (attempt $attempt, no progress — likely hung)"
        exit 124
    fi
    if [ "$rc" -ne 75 ]; then
        echo "::error::$what failed (exit $rc) — not a transient signal (75), not retrying"
        exit "$rc"
    fi
    now=$(date +%s)
    if [ "$now" -ge "$deadline" ]; then
        echo "::error::$what still transiently failing after ${TIMEOUT_SECONDS}s (attempt $attempt) — giving up"
        exit 75
    fi
    remaining=$(( deadline - now ))
    [ "$delay" -gt "$remaining" ] && delay="$remaining"
    echo "::warning::$what transient failure (attempt $attempt); retrying in ${delay}s"
    "$sleep_cmd" "$delay"
    delay=$(( delay * 2 ))
    [ "$delay" -gt "$MAX_DELAY_SECONDS" ] && delay="$MAX_DELAY_SECONDS"
done

# Phase 0 — recorded decisions

Pinned facts resolved by the Phase 0 spikes. Every value here is consumed by a
later phase (a `.dockerfile` syntax line, a `.env`, a Renovate rule, or a JUnit
guard). Do not leave `<placeholder>` tokens: a row lands here only once resolved
against live infrastructure.

## Spike A — dockerfile-x frontend (digest + ARG-before-INCLUDE)

Frontend: `devthefuture/dockerfile-x`, pinned to `1.6.0` by manifest-list digest
(multi-arch safe). Re-confirmed live on 2026-06-11 via
`docker buildx imagetools inspect docker.io/devthefuture/dockerfile-x:1.6.0`
(daemonless — no build needed):

```
sha256:000d1ae882609bf9a7a3aa4647370d55ffb769580ea5895987192996ffed159f
```

`SyntaxPinTest` (Phase 3) reads the full pinned `# syntax=` line from the one
key below; every `docker/images/*.dockerfile` first line must be byte-identical:

DOCKERFILE_X_SYNTAX: # syntax=docker.io/devthefuture/dockerfile-x:1.6.0@sha256:000d1ae882609bf9a7a3aa4647370d55ffb769580ea5895987192996ffed159f

### ARG-before-INCLUDE bug and the safe thin-file shape

dockerfile-x drops/ignores an `ARG` declared *before* the first `INCLUDE` —
an established upstream behavior of `devthefuture/dockerfile-x` that the thin-file
design already accounts for. The safe shape is to pass build args through
`INCLUDE_ARGS images/<slug>.env` placed BEFORE the first `INCLUDE`; values
flow into the included fragments rather than being dropped. So each thin
`.dockerfile` puts `INCLUDE_ARGS` first, then `INCLUDE`s.

The local `docker build` repro of the bug and the `INCLUDE_ARGS` confirmation
are DEFERRED to CI: they require a running Docker daemon (unavailable in this
Phase-0 environment), and Phase 5's resolve harness exercises real dockerfile-x
against a daemon. This section records the known behavior and the adopted safe
shape; it does not claim a local repro that was not run.

## Spike B — qodana-cli-deps clang-tidy mirror

URL template (from `qodana-cli` `scripts/downloaddeps/clang-tidy.json`):

```
https://packages.jetbrains.team/files/p/sa/qodana-cli-deps/clang-tidy/$version/$filename
```

`$version` is the clang-tidy package version tag (e.g. `v1.0.0`), NOT the clang
compiler version. `$filename` is `clang-tidy-<os>-<arch>.tar.gz` (`.zip` on
windows): `clang-tidy-linux-amd64.tar.gz`, `clang-tidy-linux-arm64.tar.gz`, etc.
Resolved example:
`https://packages.jetbrains.team/files/p/sa/qodana-cli-deps/clang-tidy/v1.0.0/clang-tidy-linux-amd64.tar.gz`.
Renovate tracks new versions via
`https://packages.jetbrains.team/files/p/sa/qodana-cli-deps/clang-tidy/versions.json`.

CLANG_TIDY_URL_TEMPLATE = https://packages.jetbrains.team/files/p/sa/qodana-cli-deps/clang-tidy/$version/$filename

### Access mode: PRIVATE (token required)

Anonymous `curl -I` of the mirror root returns `HTTP/2 401` with
`www-authenticate: Basic` (probed live 2026-06-11). `qodana-cli`'s
`CONTRIBUTING.md` confirms a `QODANA_CLI_DEPS_TOKEN` read token is required;
without it `go generate` writes empty placeholders. The clang image build must
supply this token (HTTP basic) to fetch the archive — CI provisions it.

CLANG_TIDY_ACCESS = private

## Spike C — live feed pin, dhi.io base digest, JetBrains key

_pending_

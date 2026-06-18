# docker/ — image composition

Each linter image is one thin `images/<slug>.dockerfile` that `INCLUDE`s shared `lib/` fragments via
[dockerfile-x](https://github.com/devthefuture-org/dockerfile-x). Build instructions are in
[../README.md](../README.md); this file is the include graph and the add-a-linter recipe.

## Why composition, not a base-image chain

Each `lib/` include owns one orthogonal concern, so a linter pulls only what it needs — no image
inherits another. The thin file lists `INCLUDE_ARGS images/<slug>.env` **first** (dockerfile-x drops an
`ARG` declared before the first `INCLUDE`, so build args must arrive via `INCLUDE_ARGS`), then the
includes in composition order.

| `lib/` include      | concern                                                                                             | used by                              |
| ------------------- | --------------------------------------------------------------------------------------------------- | ------------------------------------ |
| `base`              | dhi.io hardened OS, `qodana` user, writable dirs, `QODANA_*` env, locale                            | all                                  |
| `toolchain/node`    | Node + Yarn for JS/TS analysis (in-place — no `FROM`, extends the current stage)                    | jvm, python                          |
| `toolchain/android` | Android SDK + Corretto                                                                              | android                              |
| `toolchain/clang`   | clang/clang++/cmake (apt + LLVM repo)                                                               | clang                                |
| `toolchain/conda`   | Miniconda3 (sha-pinned) + poetry + pipenv (pip, version-pinned)                                     | python, python-community             |
| `toolchain/eslint`  | global ESLint (pinned in `eslint/package.json`, renovate-npm-tracked) for JS/TS analysis (in-place) | js (later go/php/ruby)               |
| `dist`              | download + GPG/sha256-verify the IDE dist (`provision-dist`), then `verify-dist-layout`             | jvm, android, python(-community), js |
| `privileged`        | passwordless `sudo` for the `qodana` user                                                           | clang                                |
| `tools`             | clang-tidy from the private qodana-cli-deps mirror                                                  | clang                                |
| `cli`               | install the inner CLI (`install-cli`; release download or from-tree context)                        | all                                  |
| `runtime`           | `tini` PID 1, drop to `qodana`, `WORKDIR`, `ENTRYPOINT` (execs `${CLI_BINARY}`, forwards args)      | all                                  |

Resolved stage lineage of the final image:

    qodana-jvm:              base → node → dist → cli → runtime
    qodana-android:          base → android-toolchain → dist → cli → runtime   (dist FROMs android-toolchain via DIST_BASE_STAGE)
    qodana-python-community: base → conda-toolchain → dist → cli → runtime            (dist FROMs conda-toolchain via DIST_BASE_STAGE)
    qodana-python:           base → conda-toolchain(+node) → dist → cli → runtime      (node appends onto conda-toolchain; dist FROMs it via DIST_BASE_STAGE)
    qodana-js:               base → eslint → dist → cli → runtime             (node + Yarn from the dhi.io/node base; eslint in-place on base so dist FROMs base; QODANA_UID=1001)
    qodana-clang:            base → clang-toolchain → privileged → tools → cli → runtime   (no dist; CLI_BASE_STAGE=tools)

`node`/`android-toolchain`/`conda-toolchain` etc. must land in the final image's lineage, so `dist`
builds `FROM ${DIST_BASE_STAGE:-base}` (android sets it to `android-toolchain`, python to
`conda-toolchain`; jvm keeps `base`) and `cli` builds `FROM ${CLI_BASE_STAGE}` (clang sets it to
`tools`, which has no dist).

## Pins and verification

Every external artifact is pinned by digest/build and verified fail-closed: the IDE dist by GPG
signature (key vendored in `lib/jetbrains.pub`) then sha256; `ADD --checksum=` for tini and the Android
SDK; the dhi.io base and the dockerfile-x frontend by `@sha256`. Pins live in `images/<slug>.env` +
`../docs/phase-0-decisions.md` (kept byte-identical by `EnvContractTest`) and are Renovate-tracked; the
scheduled `Linter image drift` workflow re-verifies and bumps them.

`base` parameterizes the `qodana` user via `QODANA_UID`/`QODANA_GID` (default 1000, declared once in
`base`'s global pre-FROM block, so the existing debian-base images are byte-identical; the consuming
`dist`/`cli`/`runtime` stages re-declare them bare to inherit the value and avoid the `cli.dockerfile`
shadowing trap). A DHI language base whose own user already occupies uid 1000 (the `node` base ships
`node` at 1000) overrides them to 1001 — passed as a **compose build arg** (mirroring clang's
`CLI_BASE_STAGE`), NOT an `.env` key: dockerfile-x emits each `INCLUDE_ARGS` key as an `ARG NAME="val"`
default that `base`'s own `ARG QODANA_UID=1000` (emitted later) would clobber, whereas a `--build-arg`
always wins.

## Add a linter

1. `images/<slug>.dockerfile`: first line = the pinned `# syntax=…dockerfile-x@sha256:…` (`SyntaxPinTest`
   enforces it), then `INCLUDE_ARGS images/<slug>.env`, then the `INCLUDE`s for the concerns it needs.
2. `images/<slug>.env`: the build args/pins (base digest, dist version/build, `CLI_BINARY`, etc.).
   Mirror any new pin into `../docs/phase-0-decisions.md` so `EnvContractTest` stays green.
3. A service in `../compose.yaml` (+ overlays if it needs a private token).
4. Validate (no daemon): `./gradlew :linter-images:test` (env/compose/syntax guards) and
   `prek run resolve-hadolint` (resolves + hadolints every composition; prek provisions hadolint).

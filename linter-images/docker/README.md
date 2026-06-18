# docker/ — image composition

Each linter image is one thin `images/<slug>.dockerfile` that `INCLUDE`s shared `lib/` fragments via
[dockerfile-x](https://github.com/devthefuture-org/dockerfile-x). Build instructions are in
[../README.md](../README.md); this file is the include graph and the add-a-linter recipe.

## Why composition, not a base-image chain

Each `lib/` include owns one orthogonal concern, so a linter pulls only what it needs — no image
inherits another. The thin file lists `INCLUDE_ARGS images/<slug>.env` **first** (dockerfile-x drops an
`ARG` declared before the first `INCLUDE`, so build args must arrive via `INCLUDE_ARGS`), then the
includes in composition order.

| `lib/` include       | concern                                                                                                    | used by                                                        |
| -------------------- | ---------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `base`               | dhi.io hardened OS, `qodana` user, writable dirs, `QODANA_*` env, locale                                   | all                                                            |
| `toolchain/node`     | Node + Yarn for JS/TS analysis (in-place — no `FROM`, extends the current stage)                           | jvm, python, go, php, ruby                                     |
| `toolchain/android`  | Android SDK + Corretto                                                                                     | android                                                        |
| `toolchain/clang`    | clang/clang++/cmake (apt + LLVM repo)                                                                      | clang                                                          |
| `toolchain/conda`    | Miniconda3 (sha-pinned) + poetry + pipenv (pip, version-pinned)                                            | python, python-community                                       |
| `toolchain/eslint`   | global ESLint (pinned in `eslint/package.json`, renovate-npm-tracked) for JS/TS analysis (in-place)        | js, go, php, ruby                                              |
| `toolchain/go`       | redirect `GOMODCACHE` to the writable `/data/cache` (Go is pre-baked in the golang base; in-place)         | go                                                             |
| `toolchain/ruby`     | redirect the gem/bundle cache to the writable `/data/cache` (Ruby is pre-baked in the ruby base; in-place) | ruby                                                           |
| `toolchain/composer` | Composer (`COPY --from` the pinned `COMPOSER_IMAGE`); opens `php-toolchain` stage (`FROM base`)            | php                                                            |
| `toolchain/dotnet`   | .NET SDKs (8/9/10) via a pinned `dotnet-install.sh`                                                        | cdnet                                                          |
| `dist`               | download + GPG/sha256-verify the IDE dist (`provision-dist`), then `verify-dist-layout`                    | jvm, android, python(-community), js, go, php, ruby(-3.2/-3.4) |
| `privileged`         | passwordless `sudo` for the `qodana` user (FROMs `${PRIVILEGED_BASE_STAGE}`)                               | clang, cdnet, ruby (first to combine privileged + dist)        |
| `tools`              | clang-tidy from the private qodana-cli-deps mirror                                                         | clang                                                          |
| `resharper-clt`      | ReSharper CLT (InspectCode) from the private qodana-cli-deps mirror                                        | cdnet                                                          |
| `cli`                | install the inner CLI (`install-cli`; release download or from-tree context)                               | all                                                            |
| `runtime`            | `tini` PID 1, drop to `qodana`, `WORKDIR`, `ENTRYPOINT` (execs `${CLI_BINARY}`, forwards args)             | all                                                            |

Resolved stage lineage of the final image:

    qodana-jvm:              base → node → dist → cli → runtime
    qodana-android:          base → android-toolchain → dist → cli → runtime   (dist FROMs android-toolchain via DIST_BASE_STAGE)
    qodana-python-community: base → conda-toolchain → dist → cli → runtime            (dist FROMs conda-toolchain via DIST_BASE_STAGE)
    qodana-python:           base → conda-toolchain(+node) → dist → cli → runtime      (node appends onto conda-toolchain; dist FROMs it via DIST_BASE_STAGE)
    qodana-js:               base → eslint → dist → cli → runtime             (node + Yarn from the dhi.io/node base; eslint in-place on base so dist FROMs base; QODANA_UID=1001)
    qodana-go:               base → go → node → eslint → dist → cli → runtime   (Go pre-baked in the dhi.io/golang base; go/node/eslint in-place on base so dist FROMs base; golang base does not occupy uid 1000, so default 1000)
    qodana-php:              base → node → eslint → php-toolchain(composer) → dist → cli → runtime   (PHP pre-baked in the dhi.io/php base; node/eslint in-place on base, composer COPYd onto php-toolchain (FROM base); dist FROMs php-toolchain via DIST_BASE_STAGE; php base does not occupy uid 1000, so default 1000)
    qodana-rust:             base → rust-toolchain → dist → cli → runtime            (no Rust in the trixie base, so rustup-init installs the toolchain in rust-toolchain (FROM base, the conda pattern); dist FROMs rust-toolchain via DIST_BASE_STAGE; no node — RustRover has no JS analysis; trixie base does not occupy uid 1000, so default 1000)
    qodana-ruby[-3.2|-3.4]:  base → node → eslint → ruby → privileged(FROM base) → dist(FROM privileged) → cli → runtime   (ruby pre-baked in the dhi.io/ruby base; node/eslint/ruby in-place on base; privileged FROMs base via the PRIVILEGED_BASE_STAGE=base build arg; dist FROMs privileged via DIST_BASE_STAGE=privileged (.env key); 3 variants differ only in QD_BASE_IMAGE; ruby base does not occupy uid 1000, so default 1000; the shipped image carries sudo for project-gem installs, source PRIVILEGED=true)
    qodana-clang:            base → clang-toolchain → privileged → tools → cli → runtime   (no dist; CLI_BASE_STAGE=tools)
    qodana-cdnet:            base → dotnet-toolchain → privileged → tools(CLT) → cli → runtime   (no dist; CLI_BASE_STAGE=tools, PRIVILEGED_BASE_STAGE=dotnet-toolchain)
    qodana-dotnet:           base → node → eslint → dotnet-toolchain(FROM base) → privileged(FROM dotnet-toolchain) → dist(FROM privileged) → cli → runtime   (Ultimate Rider; no .NET in the trixie base, so the shared dotnet-toolchain installs SDK 8/9/10; node/eslint in-place on base (Rider analyzes JS/TS), dotnet-toolchain FROMs base and inherits them; privileged FROMs dotnet-toolchain via PRIVILEGED_BASE_STAGE build arg; dist FROMs privileged via DIST_BASE_STAGE=privileged (.env key); NO resharper-clt — InspectCode ships in the Rider dist; LIBICU_PKG=libicu76 (trixie); trixie base does not occupy uid 1000, so default 1000; shipped image carries sudo, source PRIVILEGED=true)

`node`/`android-toolchain`/`conda-toolchain` etc. must land in the final image's lineage, so `dist`
builds `FROM ${DIST_BASE_STAGE:-base}` (android sets it to `android-toolchain`, python to
`conda-toolchain`, php to `php-toolchain`, rust to `rust-toolchain`, ruby and dotnet to `privileged`; jvm keeps `base`), `privileged` builds `FROM ${PRIVILEGED_BASE_STAGE}` (cdnet and dotnet set it
to `dotnet-toolchain`, ruby to `base`; clang relies on the `clang-toolchain` global default in `base.dockerfile`), and
`cli` builds `FROM ${CLI_BASE_STAGE}` (clang/cdnet set it to `tools`, which has no dist). `DIST_BASE_STAGE` is an
`.env` key (`base.dockerfile` does not declare it, so the value survives), but `PRIVILEGED_BASE_STAGE`/`CLI_BASE_STAGE`
are compose build args (`base.dockerfile` declares defaults for them, so an `.env` value would be clobbered — a
`--build-arg` must win). Ruby is the first image to combine `privileged` with a `dist` (its dist FROMs `privileged`,
so the shipped runtime carries sudo); dotnet does the same but layers `privileged` onto the shared `dotnet-toolchain`
(PRIVILEGED_BASE_STAGE=dotnet-toolchain) rather than `base`.

cdnet's CLT installs under `/opt/<image>/bin` (on PATH) — `/opt/qodana-cdnet/bin/inspectcode`, found by
name — distinct from `/data/cache`, the runtime cache where clang's `tools` pre-stages clang-tidy
(`/data/cache/tools`) so the qodana-clang entrypoint resolves it by cacheDir contract.

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

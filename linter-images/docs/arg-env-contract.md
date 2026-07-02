# ARG/ENV contract

Single source of truth for every build ARG / runtime ENV the thin images pass to `lib/` includes.
Every `images/<slug>.env` MUST set exactly the keys its INCLUDEs consume — no undefined args, no
extras. The Phase-4 validation test (`EnvContractTest`) asserts each `.env` against this table.

This table lists the `.env` keys `EnvContractTest` asserts. (Build ARGs with include-side defaults — `QD_DISTRIBUTION_FEED` (public feed; private images override it on the build command), `QD_DIST`, `QD_GPG_FINGERPRINT` via `$(cat .fpr)`, `CLI_SOURCE`, `CLI_BASE_STAGE`, `PRIVILEGED_BASE_STAGE`, `CLANG_TIDY_SHA256`, `CLANG_TIDY_TOOLS_DIR`, `CLT_SHA256`, `JDK_BUILDER_IMAGE` — are NOT `.env` keys; see the Phase-2b/3 "ARG/ENV contract" table for those.)

`CLI_BASE_STAGE` and `PRIVILEGED_BASE_STAGE` are stage selectors the compose service passes, NOT `.env` keys. `CLI_BASE_STAGE` picks the stage `lib/cli.dockerfile` FROMs (clang/cdnet set `tools`, which has no dist). `PRIVILEGED_BASE_STAGE` picks the stage `lib/privileged.dockerfile` FROMs: cdnet sets `dotnet-toolchain`; everyone else relies on the `clang-toolchain` global default in `lib/base.dockerfile`.

| `.env` Key             | Consumed by include                                     | Source                   | Notes                                                                                |
| ---------------------- | ------------------------------------------------------- | ------------------------ | ------------------------------------------------------------------------------------ |
| `QD_LINTER_SLUG`       | `lib/dist.dockerfile` (provision-dist)                  | per-linter               | `qodana-jvm`; android reuses `qodana-jvm`; clang unset                               |
| `QD_VERSION`           | `lib/dist.dockerfile` (provision-dist `--version`)      | phase-0-decisions.md     | engine MAJOR `2026.1`; jvm+android share it; clang unset                             |
| `QD_BUILD`             | `lib/dist.dockerfile` (provision-dist `--build`)        | phase-0-decisions.md     | EXACT pinned build `261.…`; jvm+android share it; clang unset; drift bot rewrites    |
| `QD_PRODUCT_INFO_CODE` | `lib/dist.dockerfile` (verify-dist-layout)              | IntellijLinterProperties | product-info.json code: `IU` for jvm+android; clang unset                            |
| `QD_BASE_IMAGE`        | `lib/base.dockerfile`                                   | phase-0-decisions.md     | `dhi.io/...@sha256:<digest>`, Renovate-tracked                                       |
| `DIST_BASE_STAGE`      | `lib/dist.dockerfile` (`FROM ${DIST_BASE_STAGE:-base}`) | per-linter               | android only: `android-toolchain` so dist inherits the SDK; jvm/clang unset → `base` |
| `CLI_BINARY`           | `lib/cli.dockerfile` (install-cli)                      | per-linter               | `qodana` (jvm/android) or `qodana-clang` (clang)                                     |
| `CLI_VERSION`          | `lib/cli.dockerfile` (install-cli)                      | gradle.properties        | `2026.2`, independent of engine                                                      |
| `CLI_OS`               | `lib/cli.dockerfile` (install-cli)                      | constant                 | `linux`                                                                              |
| `NODE_MAJOR`           | `lib/toolchain/node.dockerfile`                         | per-linter               | jvm only                                                                             |
| `ANDROID_SDK_VERSION`  | `lib/toolchain/android.dockerfile`                      | phase-0                  | android only                                                                         |
| `ANDROID_SDK_SHA256`   | `lib/toolchain/android.dockerfile`                      | phase-0                  | android only                                                                         |
| `CORRETTO11_IMAGE`     | `lib/toolchain/android.dockerfile`                      | phase-0                  | android only, by digest                                                              |
| `CORRETTO17_IMAGE`     | `lib/toolchain/android.dockerfile`                      | phase-0                  | android only, by digest                                                              |
| `DEVICEID`             | `lib/toolchain/android.dockerfile`                      | per-linter               | android only                                                                         |
| `CLANG`                | `lib/toolchain/clang.dockerfile`                        | clang-versions.txt       | clang major; CI matrix overrides per row (clang only)                                |
| `CLANG_OS`             | `lib/toolchain/clang.dockerfile`                        | clang-versions.txt       | debian codename for the clang major; CI matrix overrides (clang only)                |
| `CLANG_TIDY_VERSION`   | `lib/tools.dockerfile`                                  | phase-0-decisions.md     | clang-tidy pin (clang only)                                                          |
| `CLANG_TIDY_MIRROR`    | `lib/tools.dockerfile`                                  | phase-0-decisions.md     | qodana-cli-deps Space mirror base URL (clang only)                                   |
| `CLT_VERSION`          | `lib/resharper-clt.dockerfile`                          | phase-0-decisions.md     | ReSharper CLT (InspectCode) pin (cdnet only)                                         |
| `CLT_MIRROR`           | `lib/resharper-clt.dockerfile`                          | phase-0-decisions.md     | qodana-cli-deps Space mirror base URL (cdnet only)                                   |

Arch is NOT an `.env` key (QD-15172). `lib/cli.dockerfile`/`lib/dist.dockerfile` derive it from BuildKit's `TARGETARCH` (`install-cli`/`provision-dist` `--arch "${TARGETARCH}"`), and `lib/runtime.dockerfile` pins tini (version + both per-arch shas) in the dockerfile itself, selecting `COPY --from=tini-${TARGETARCH}`. So `CLI_ARCH`/`TINI_VERSION`/`TINI_ARCH`/`TINI_SHA256` no longer exist; `EnvContractTest` asserts they are absent. (clang/cdnet's clang-tidy mirror arch comes from `tools.dockerfile`'s own `ARG CLI_ARCH=amd64` default, not from an `.env` key.)

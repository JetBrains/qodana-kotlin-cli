# Linter-image e2e tracks

End-to-end tests for the in-repo Qodana linter images (`qodana-jvm`, `qodana-android`,
`qodana-clang`). Each image gets its own CI job — **`Docker e2e (<image>)`** — that builds the image
from this tree and runs every fixture under `fixtures/<image>/`, asserting the produced
`qodana.sarif.json` against a per-case `expected.json`. A job goes red on any problem with its image,
and only its image (`fail-fast: false`).

These are regression-grade, not smoke: each fixture plants real defects (and known false-positives to
stay absent), with assertions tied to the YouTrack tickets they guard.

## Layout

```
linter-images/e2e/
  expected-manifest.schema.json     # JSON Schema for expected.json (validated by a unit test)
  fixtures/<image>/<case>/
    project/                        # the real project, mounted at /data/project (carries its qodana.yaml)
    expected.json                   # run config + SARIF/log assertions for this case
```

`<image>` ∈ {`qodana-jvm`, `qodana-android`, `qodana-clang`} (`qodana-cpp` is reserved for later).

## Running

- **CI (the real run, amd64):** the `e2e` matrix job builds `<image>:dev` then runs
  `./gradlew :linter-images:linterE2eTest -Dlinter.e2e.image=<image>`. The harness discovers
  `fixtures/<image>/*/expected.json`, emits one test per `(case, variant)`, shells `docker run … scan`,
  and asserts. clang is token-gated (`QODANA_CLI_DEPS_TOKEN`) and cleanly no-ops on fork PRs.
- **Locally:** the images are amd64-only, so the `@Tag("linter-e2e")` tests are **CI-only**. What runs
  locally is the unit layer — `./gradlew :linter-images:test` (manifest model + schema, the SARIF
  evaluator, the docker-arg planner, and `FixtureManifestsTest`, which loads every `expected.json`).
  If you have an amd64 Docker host, you can run a single image's tracks the same way CI does.

## The `expected.json` manifest

Conforms to `expected-manifest.schema.json` (and the Kotlin model in
`src/test/kotlin/org/jetbrains/qodana/images/e2e/ExpectedManifest.kt`). Shape:

- `case`, `image`, `description` — `case`/`image` must match the `<image>/<case>` directory names.
- `run` — `network` (`none` for hermetic jvm/clang, `bridge` for android), `capAdd`, `securityOpt`,
  `extraArgs` (extra `scan` flags), `env`, `failThreshold`, and `variants[]` (each names its own
  `qodanaYaml` relative to `project/`, and a `gitState` of `none` | `init-no-head` | `clean`).
- `expectExitCode` (default 0).
- `sarif` — `tool.{driverName,driverFullName}`, `qodanaSeverityRequired`, and `expectations[]`. Each
  expectation keys on `ruleId` (exact) or `ruleIdPattern` (regex, for whole families) — or neither, to
  scope purely by `uriContains` — with `presence` (`present`|`absent`), `count` (`>=N`|`==N`|`==0`),
  `uriContains`, `messageContains`, `variant`, `pin` (`confirmed`|`needs-pinning`), `guards` (ticket
  ids), and a mandatory `reason`.
- `log` — `mustNotContain` / `mustContain` substrings checked against `log/idea.log`.

### Hermeticity

jvm and clang cases run with `network: none` — a stray network access fails the scan, so hermeticity
is enforced, not hoped for. android needs the network (its `qodana.yaml` `bootstrap` installs the SDK
platform via `sdkmanager`, the documented customer flow), so it runs on `bridge`.

### `pin: "needs-pinning"`

Exact rule ids and the per-image SARIF driver name depend on the image's bundled inspection profile and
can only be observed from a real scan (CI, amd64). Such expectations start with a *guessed* id marked
`pin: "needs-pinning"`; the first CI run surfaces the real value (the harness prints unmatched
expectations), which is then committed. For control/treatment cases, an `absent`+`needs-pinning`
expectation is resolved automatically from the control variant's report at runtime.

## Adding a case

1. Create `fixtures/<image>/<new-case>/project/` (a minimal real project + its `qodana.yaml`) and
   `expected.json`.
2. `./gradlew :linter-images:test --tests '*Manifest*'` — `FixtureManifestsTest` validates the new
   manifest with no Docker.
3. Push; read the `Docker e2e (<image>)` run, pin any `needs-pinning` ids, re-run to green.

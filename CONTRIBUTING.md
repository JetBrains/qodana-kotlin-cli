# Contributing to qodana-kotlin-cli

## Local development

### Prerequisites

- **JDK 21** for normal builds and tests. Any vendor works (Temurin, Zulu, GraalVM CE).
- **GraalVM CE 21** for `nativeCompile`. The build pins `languageVersion=21` + `vendor=GRAAL_VM` in [qodana-cli/build.gradle.kts](qodana-cli/build.gradle.kts) and the [foojay toolchain resolver](https://github.com/gradle/foojay-toolchains) auto-downloads a matching JDK on first run if `JAVA_HOME` doesn't already point at one. Any GraalVM CE 21 patch release is accepted; foojay picks one of the latest at the time of first build.
- **`./gradlew`** — checked-in Gradle wrapper handles the rest.

If you prefer to install GraalVM yourself, the cleanest path is [SDKMAN](https://sdkman.io):

```sh
sdk install java 21-graalce
sdk use java 21-graalce
```

The Gradle daemon then picks up GraalVM via the toolchain pin in [qodana-cli/build.gradle.kts](qodana-cli/build.gradle.kts).

### Run the test suite

```sh
./gradlew test
```

To run the Docker-touching tests (gated on `QODANA_TEST_CONTAINER=1`):

```sh
QODANA_TEST_CONTAINER=1 ./gradlew parityTest
```

## Building the native binary

The qodana-cli executable can be compiled ahead-of-time into a self-contained native binary via [GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/). The binary runs without a JVM dependency.

### Quick build

```sh
./gradlew :qodana-cli:nativeCompile
```

Produces `qodana-cli/build/native/nativeCompile/qodana-cli` (or `.exe` on Windows). Cold builds take 3–6 minutes on Apple Silicon; subsequent builds are faster thanks to Gradle's build cache.

`native-image` **cannot cross-compile** — to ship Linux/Windows binaries you must build on each target OS. Phase B ([QD-14720](https://youtrack.jetbrains.com/issue/QD-14720)) wires this into a GitHub Actions matrix.

### Reachability metadata

GraalVM static analysis can't see code reached only via reflection, `Class.forName`, `ServiceLoader`, Jackson annotations, etc. Such usages must be declared in JSON metadata files committed under:

```
qodana-cli/src/main/resources/META-INF/native-image/org.jetbrains.qodana/qodana-cli/
```

Two sources contribute:

1. The upstream [GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata) — covers Jackson, OkHttp 4.x, slf4j, kotlin-stdlib, kotlinx-coroutines. Enabled in [build-logic/src/main/kotlin/graalvm-native.gradle.kts](build-logic/src/main/kotlin/graalvm-native.gradle.kts) via `metadataRepository { enabled.set(true) }`. Downloads on the first `nativeCompile` invocation.
2. The tracing agent run over our own tests — captures qodana-specific reflection (`QodanaYaml`, `IdeProductInfoJson`, etc.). Committed JSON regenerated on demand by the executor.

### Regenerating tracing-agent metadata

Run after bumping any dependency that touches reflection, or after adding code that uses reflection / `ServiceLoader` / classpath resources:

```sh
# 1) Generate metadata under the agent.
#    Keep the --tests filter narrow — running the full suite under the agent
#    bloats the captured config with test-only entries and Docker DTOs.
./gradlew -Pagent :qodana-cli:test --rerun-tasks \
    --tests 'org.jetbrains.qodana.cli.NativeSmokeTest' \
    --tests 'org.jetbrains.qodana.cli.command.InitCommandTest'

# 2) Copy captured JSON into src/main/resources.
./gradlew :qodana-cli:metadataCopy

# 3) Strip JUnit + kotlin-test infrastructure that the agent captured but the
#    production binary doesn't need. The graalvm-native plugin's accessFilterFiles
#    knob doesn't reliably catch these on v0.10.6 — manual cleanup is the
#    least-bad option until that's fixed upstream. Drop entries matching:
#      - "org.jetbrains.qodana.cli.NativeSmokeTest"
#      - "org.jetbrains.qodana.cli.command.InitCommandTest"
#      - "META-INF/services/org.junit.platform.*"
#      - "META-INF/services/kotlin.test.AsserterContributor"
#      - "junit-platform.properties"
#    Use `git diff` against the previous committed metadata as a guide.

# 4) Inspect the diff and rebuild the native image.
git status qodana-cli/src/main/resources/META-INF/native-image/
./gradlew :qodana-cli:nativeCompile
```

If `nativeCompile` reports `Classes that should be initialized at run time got initialized during image building`, add the named class as `--initialize-at-run-time=<class-or-package>` in [build-logic/src/main/kotlin/graalvm-native.gradle.kts](build-logic/src/main/kotlin/graalvm-native.gradle.kts) and re-run. Likely candidates: `org.slf4j.simple`, `okhttp3.internal.platform`.

If a smoke command fails at runtime with `MissingReflectionRegistrationError`, the agent didn't see that code path. Extend [NativeSmokeTest.kt](qodana-cli/src/test/kotlin/org/jetbrains/qodana/cli/NativeSmokeTest.kt) to exercise it, then re-run the cycle.

### Phase-A scope

Phase A ([QD-14643](https://youtrack.jetbrains.com/issue/QD-14643)) limits the native binary's working surface to `--help`, `--version`, `init`, and per-subcommand `--help`. `scan`, `view`, `send`, `pull`, `show` parse their options but their `run()` bodies are not validated under native execution — that's tracked in [QD-14728](https://youtrack.jetbrains.com/issue/QD-14728).

## Releases

The release pipeline is documented in [docs/release.md](docs/release.md). Key points for contributors:

- **Version source of truth:** [gradle.properties](gradle.properties)'s `version=` line. Default is `dev` (development state — `SystemUtils.checkForUpdates` skips network calls). To start a release cycle, bump to a numeric version that satisfies the bump rule (matches the most recent stable `v*` tag, or is exactly one semantic bump ahead).
- **Pre-push enforcement:** `./gradlew checkVersion` is wired into [.pre-commit-config.yaml](.pre-commit-config.yaml)'s `pre-push` stage. Pushes with a version that skips a segment are rejected.
- **Runtime version overrides** (`-Dqodana.version=…`, `QODANA_VERSION=…`) remain supported for local JVM dev only — see [QodanaCommand.kt](qodana-cli/src/main/kotlin/org/jetbrains/qodana/cli/command/QodanaCommand.kt) `companion object`. Native binaries bake the version in via `BuildInfo.VERSION` (generated from `project.version` at build time) and ignore the runtime overrides under `--initialize-at-build-time`.
- **The pre-push hook also guards** against accidental reintroduction of `qodana.version` / `QODANA_VERSION` reads outside the two authorized locations (`QodanaCommand.kt` and `SystemUtilsTest.kt`).

## Troubleshooting

### `Cannot query the value of this property because it has no value available`

`-Pagent` runs trigger a post-test merge step that needs the GraalVM toolchain's `native-image-configure` binary. If foojay's downloaded toolchain is incomplete (zero-byte `bin/native-image*` placeholders), the binaries can be symlinked from `lib/svm/bin/`:

```sh
cd ~/.gradle/jdks/graalvm_community-21-aarch64-os_x.2/graalvm-community-openjdk-21.0.2+13.1/Contents/Home/bin
rm native-image native-image-configure
ln -s ../lib/svm/bin/native-image native-image
ln -s ../lib/svm/bin/native-image-configure native-image-configure
```

Then re-run with `GRAALVM_HOME` and `JAVA_HOME` pointing at the toolchain.

### Corporate proxy

`metadataRepository { enabled.set(true) }` fetches from the public GraalVM mirror. Behind a proxy, set the standard Gradle proxy properties (`-Dhttps.proxyHost=...`) or pin a local mirror. See [Gradle's HTTP settings](https://docs.gradle.org/current/userguide/build_environment.html#sec:accessing_the_web_via_a_proxy).

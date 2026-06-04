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

### Release tooling scripts

Release/version logic lives in the `release-tools` module as unit-tested Kotlin cores
(`./gradlew :release-tools:test`). Thin `*.main.kts` wrappers in `release-tools/scripts/` drive the CI
workflows and are runnable by hand. They need the **pinned** Kotlin compiler (version in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml)'s `kotlin = "…"`) — the same one CI installs via
[`.github/actions/setup-kotlin`](.github/actions/setup-kotlin/action.yaml):

    sdk install kotlin 2.1.20        # matches the pin; mirrors `sdk install java 21-graalce`
    kotlin release-tools/scripts/normalize-version.main.kts 2026.3.1
    kotlin release-tools/scripts/cleanup-old-nightlies.main.kts --keep 7 --dry-run

The pre-push `checkVersion` guard is a Gradle task (`./gradlew :release-tools:checkVersion`), so pushing
needs no Kotlin compiler — only running the scripts does.

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

Run after bumping any dependency that touches reflection, or after adding code that uses reflection / `ServiceLoader` / classpath resources. Requires GraalVM CE 21 and a running Docker daemon (the agent has to drive the Docker-tagged tests to capture docker-java DTOs):

```sh
# 1) Generate metadata under the agent for BOTH the regular `test` task
#    (non-Docker reflection: Clikt, Jackson, InitCommand file IO, send via
#    MockQDCloudHttpClient) and the `parityTest` task (Docker-tagged tests).
#    Both runs are merged into the committed JSON via mergeWithExisting.
./gradlew -Pagent :qodana-cli:test :qodana-cli:parityTest --rerun-tasks

# 2) Copy captured JSON into src/main/resources. The
#    `stripTestEntriesFromMetadata` task runs as a finalizer of `metadataCopy`
#    and removes JUnit / kotlin-test / scan-smoke-fixture entries using the
#    canonical list in qodana-cli/src/test/resources/banned-metadata-patterns.txt.
./gradlew :qodana-cli:metadataCopy

# 3) Verify hygiene. The test enforces that no test-infrastructure entries
#    landed in the committed JSON; if it fails, the failure message names
#    exactly which entries to remove from which file (regenerate via Step 2).
./gradlew :qodana-cli:test --tests 'org.jetbrains.qodana.cli.MetadataHygieneTest'

# 4) Diff the result and rebuild the native image.
git diff qodana-cli/src/main/resources/META-INF/native-image/
./gradlew :qodana-cli:nativeCompile
```

If `nativeCompile` reports `Classes that should be initialized at run time got initialized during image building`, add the named class as `--initialize-at-run-time=<class-or-package>` in [build-logic/src/main/kotlin/graalvm-native.gradle.kts](build-logic/src/main/kotlin/graalvm-native.gradle.kts) and re-run. Likely candidates: `org.slf4j.simple`, `okhttp3.internal.platform`.

If a runtime command fails with `MissingReflectionRegistrationError`, the agent didn't see that code path. Extend [NativeSmokeTest.kt](qodana-cli/src/test/kotlin/org/jetbrains/qodana/cli/NativeSmokeTest.kt) to exercise it, then re-run the cycle.

### Bumping the smoke-test linter tag

Both the scan smoke test and the CI `native-e2e` job pin `jetbrains/qodana-jvm-community` via `qodana-jvm-community-tag` in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). When bumping the tag:

1. Update `qodana-jvm-community-tag` in `libs.versions.toml`.
2. Update the matching `image:` line in [`qodana-cli/src/test/resources/scan-smoke-fixture/qodana.yaml`](qodana-cli/src/test/resources/scan-smoke-fixture/qodana.yaml).
3. Re-run agent capture (steps 1–4 above) — new linter versions can rename rules; the `StringEquality` assertion in [`NativeSmokeTest.kt`](qodana-cli/src/test/kotlin/org/jetbrains/qodana/cli/NativeSmokeTest.kt) and the matching `grep` in `.github/workflows/ci.yaml`'s `Assert SARIF (native)` step will surface a rename clearly.

### Scope

The native binary supports the full runtime command set: `--help`, `--version`, `init` (Phase A, [QD-14643](https://youtrack.jetbrains.com/issue/QD-14643)), plus `scan`, `view`, `send`, `pull`, `show` execution (added in [QD-14728](https://youtrack.jetbrains.com/issue/QD-14728)). The CI `native-e2e` job exercises every command end-to-end against a real Docker daemon and a local mock cloud on each supported platform.

### CI platform notes

**Windows** (the `windows-latest` runner): GitHub-hosted Windows runners are nested VMs whose hypervisor blocks the additional virtualisation Docker Desktop would need to run Linux containers (see [community/discussions/25491](https://github.com/orgs/community/discussions/25491)). The runner's bundled Docker engine is in Windows-containers mode, and the `jetbrains/qodana-jvm-community` image is linux/amd64-only — so on this runner the `native-e2e` job exercises only the binary-only commands (`--version`, `--help`, `view`, `send` via the local mock cloud, `show --dir-only`). Docker-dependent steps (`scan`, `pull`, SARIF parity) are gated off via `matrix.platform.docker: false`.

**Windows on ARM**: There is no native arm64 build of `qodana-cli`. ARM Windows users are expected to run the amd64 binary under Windows 11's Prism x86 emulation, but **that binary does not currently run on Windows ARM** — it exits immediately with:

```
The current machine does not support all of the following CPU features that
are required by the image: [CX8, CMOV, FXSR, MMX, SSE, ..., AVX, AVX2,
BMI1, BMI2, FMA]. Please rebuild the executable with an appropriate setting
of the -march option.
```

The fix is to add a `-march=compatibility` (or `x86-64`) variant of the amd64 build that drops the high-end CPU-feature requirements. This is tracked separately in [QD-14819](https://youtrack.jetbrains.com/issue/QD-14819); until that's done, the CI `native-e2e` matrix does NOT include a `windows-amd64-on-arm` entry, since it would always fail at the first binary invocation regardless of metadata changes. Docker Desktop is also not preinstalled on the `windows-11-arm` runner (see [actions/partner-runner-images](https://github.com/actions/partner-runner-images/blob/main/images/arm-windows-11-image.md)), so the re-added entry will need the same Docker-less treatment as `windows-amd64`.

**macOS (Intel — dropped, [QD-14862](https://youtrack.jetbrains.com/issue/QD-14862))**: darwin-amd64 was removed from both the `native-build` and `native-e2e` matrices. The Qodana JVM linter container (IntelliJ-based) can't bind its DirectoryLock Unix-domain socket on the Lima+QEMU+containerd-snapshotter overlayfs stack that GitHub-hosted macOS Intel runners provide — tracked upstream as JetBrains [IJPL-161337](https://youtrack.jetbrains.com/issue/IJPL-161337) and [IJPL-34916](https://youtrack.jetbrains.com/issue/IJPL-34916). Seven CI iterations during QD-14728 unblocked every other layer (colima → setup-docker-action, DOCKER_HOST export, Lima writable mount for `/private/tmp/lima`, symlink canonicalization); the IDE-bootstrap UDS bind is unfixable from the CI side. Intel Mac users should use the JVM `qodana-cli` distribution or upgrade to Apple Silicon.
**macOS (Apple Silicon — `macos-15`)**: GitHub's hosted M1 arm64 runner has no working Docker path. Both colima VM backends fail at VM creation: `--vm-type qemu` panics in lima 2.1.1's hostagent (`panic: send on closed channel`, qemu_driver.go:382) because Hypervisor.framework returns `HV_UNSUPPORTED`; `--vm-type vz` refuses with "Virtualization is not available on this hardware" because the runner is itself a guest VM and M1 hardware lacks nested-virt support. Until GitHub exposes nested virtualisation on macos-15 arm64 (which requires M3+ host hardware) — or we provision a self-hosted M3+ runner — the `native-e2e` darwin-arm64 entry runs with `docker: false` and exercises only the binary-only commands (`--version`, `--help`, `view`, `send` via the local mock cloud, `show --dir-only`). Tracked in [QD-14821](https://youtrack.jetbrains.com/issue/QD-14821). Related upstream tickets: [actions/runner-images#9460](https://github.com/actions/runner-images/issues/9460), [abiosoft/colima#1427](https://github.com/abiosoft/colima/issues/1427).

## Releases

The release pipeline is documented in [docs/release.md](docs/release.md). Key points for contributors:

- **Version source of truth:** [gradle.properties](gradle.properties)'s `version=` line. Default is `dev` (development state — `SystemUtils.checkForUpdates` skips network calls). To start a release cycle, bump to a numeric version that satisfies the bump rule (matches the most recent stable `v*` tag, or is exactly one semantic bump ahead).
- **Pre-push enforcement:** `./gradlew :release-tools:checkVersion` is wired into [.pre-commit-config.yaml](.pre-commit-config.yaml)'s `pre-push` stage. Pushes with a version that skips a segment are rejected.
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

### Gradle toolchain picks wrong JDK (Corretto / JBR instead of GraalVM)

Gradle's OS-level JDK auto-detection may find another JVM (e.g. Amazon Corretto or JetBrains Runtime) and select it over the foojay-downloaded GraalVM CE. The symptom is:

```
/path/to/corretto-21.../bin/native-image wasn't found. This probably means that JDK isn't a GraalVM distribution.
```

Fix: install GraalVM CE 21 via SDKMAN and point `JAVA_HOME` at it before running Gradle:

```sh
sdk install java 21-graalce
sdk use java 21-graalce
./gradlew --stop
./gradlew :qodana-cli:nativeCompile
```

This is reliable across machines because it removes the ambiguity Gradle's auto-detection runs into when multiple JDK vendors are installed side by side.

Improving auto-detection so this workaround is unnecessary is tracked separately in [QD-14818](https://youtrack.jetbrains.com/issue/QD-14818). If you have to pin manually with `org.gradle.java.installations.paths=...` in a per-machine `gradle.properties` while that ticket is open, that file is gitignored and stays local to your machine.

### Corporate proxy

`metadataRepository { enabled.set(true) }` fetches from the public GraalVM mirror. Behind a proxy, set the standard Gradle proxy properties (`-Dhttps.proxyHost=...`) or pin a local mirror. See [Gradle's HTTP settings](https://docs.gradle.org/current/userguide/build_environment.html#sec:accessing_the_web_via_a_proxy).

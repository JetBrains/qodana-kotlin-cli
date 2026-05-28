# Spike: GraalVM Windows binaries without runtime bundling (HybridCRT) — QD-14840

> **This is a runbook for a HUMAN executor on a Windows build host.** The claude-code agent loop on macOS cannot directly drive Windows builds. The executor copies this runbook to Windows, follows it, then commits artifacts (patches, evidence text files, brief writeup) back to the macOS worktree via SSH/rsync/manual file move.
>
> **Tickets**: [QD-14840](https://youtrack.jetbrains.com/issue/QD-14840) (this spike, Phase B) — related to [QD-14812](https://youtrack.jetbrains.com/issue/QD-14812) (Phase A, PR #13).
>
> **Branch**: `azhukova/QD-14840`, based on `azhukova/QD-14812` (PR #13's branch).
>
> **Deliverable**: a working `qodana-cli.exe` (the actual Qodana CLI binary) that runs on clean Windows hosts without VC++ Redistributable, plus a brief writeup (PR description + YouTrack comment, 3-5 paragraphs). NOT a formal report.
>
> **Total effort**: 1-4 working days depending on outcome of the early "GraalVM-only" probe (Task 0.7). If the probe succeeds, ~1.5 days. If we need the full OpenJDK+GraalVM rebuild, ~3-4 days.

---

## Context

The qodana-kotlin-cli project ships three GraalVM Native Image `.exe` binaries on Windows. All three import `vcruntime140.dll` + `vcruntime140_1.dll`, breaking on clean Windows hosts without Microsoft VC++ Redistributable. The accepted-wisdom fix is Microsoft's [HybridCRT](https://github.com/microsoft/WindowsAppSDK/blob/main/docs/Coding-Guidelines/HybridCRT.md): statically link `vcruntime` + `libcpmt`, dynamically link UCRT via `ucrt.lib` import lib → `ucrtbase.dll` (ships with every Windows 10 1803+ / Server 2016+). No JDK distribution ships with HybridCRT-linked native libs today; the orthodox path is rebuilding both OpenJDK and GraalVM.

**Important risk-reduction insight** (Codex review): the vcruntime140 dependency could be introduced by GraalVM's own linker invocation (its hardcoded `/MD`) and/or by the JDK's static libs (also `/MD`-compiled). The orthodox view says both contribute, so both must be patched. But it's worth **empirically** confirming this before committing to the JDK rebuild — patching only GraalVM is a ~6h experiment vs ~12h for OpenJDK. **The plan does the GraalVM-only probe first** (Task 0.7), then commits to the full OpenJDK rebuild only if the probe shows the JDK is also implicated.

**Phase A status (PR #13, branch `azhukova/QD-14812`)**: OPEN, not merged as of plan creation. Adds (a) `NativeWindowsDepsTest` per native module (PortEx-based import parser), (b) `BundleWindowsCrt` Gradle step that copies `vcruntime140*.dll` alongside the `.exe`. The test's assertion is "every VC++ import has a co-located DLL bundled next to the .exe" — it does NOT distinguish "clean imports, no bundling needed" from "dirty imports, bundling masked it." For this spike, **the Phase A test is informational only**; the load-bearing proof of success is **`dumpbin /imports`/`/dependents` + a PortEx cross-check showing zero `vc*` runtime DLL imports**, combined with a successful `--help` on a clean Windows host.

## Why qodana-cli (not qodana-clang)

The original brief suggested `qodana-clang` as a "small" test target. **Verified locally**: `qodana-clang` has no reachability metadata directory under `src/main/resources/META-INF/native-image/`. Building `qodana-clang.exe` natively today (even before HybridCRT) would fail at runtime with `MissingReflectionRegistrationError` once Jackson tries to deserialize anything. Only `qodana-cli` has the committed metadata (`META-INF/native-image/org.jetbrains.qodana/qodana-cli/` with reflect-config.json, resource-config.json, etc.) AND pins `vendor = JvmVendorSpec.GRAAL_VM` (`qodana-cli/build.gradle.kts:16`). Main class: `org.jetbrains.qodana.cli.MainKt` (`qodana-cli/build.gradle.kts:31`). Application JVM args include `--enable-native-access=ALL-UNNAMED` (`qodana-cli/build.gradle.kts:34`).

"Build Qodana" = build `qodana-cli.exe`. The other two modules need metadata work — separate workstream, out of scope.

## Goal (success criteria)

A `qodana-cli.exe` produced by the patched-GraalVM-(±-patched-JDK-25) chain that:

1. **`dumpbin /imports qodana-cli.exe`** AND **`dumpbin /dependents qodana-cli.exe`** AND a PortEx cross-check list NONE of the DLLs matched by this regex: `(?i)(vcruntime|msvcp|concrt|msvcr|vcomp|mfc|mfcm|vcamp|vccorlib|atl)[0-9_]*\.dll`. Allowed: `api-ms-win-crt-*.dll`, `kernel32.dll`, `advapi32.dll`, `ws2_32.dll`, `ucrtbase.dll`, Windows-native DLLs. (The regex matches PR #13's existing VC_RUNTIME_REGEX plus the broader MSVC variants Codex flagged: `msvcp140_*`, `vcruntime140_*` arbitrary suffix, `vccorlib140`.)

2. **`qodana-cli.exe --help` exits cleanly** on:
   - `mcr.microsoft.com/windows/servercore:ltsc2022` container with `--isolation=process` (NOT default Hyper-V isolation; we want to match real customer environments where Server Core runs without Hyper-V)
   - A fresh Windows VM with no VC++ Redistributable installed

3. **Three import-set checks agree** (dumpbin /imports, dumpbin /dependents, PortEx) — no discrepancy.

"WORKING" = `--help` exits 0 with expected output. We do NOT invoke `qodana-cli scan` (requires Qodana Docker images, real project — beyond spike scope). `--help` exercises native-image startup, classloading, Clikt parsing, Jackson init, and the committed reachability metadata path.

## Critical stop conditions (precise budgets)

In every stop case, the deliverable is the documented failure with evidence; spike is "done" with a negative verdict.

- **Step 0.7 (GraalVM-only probe)**: 1 patch + 1 build + 1 retry (≤6h). If GraalVM-only patch can't produce a HelloWorld.exe without `vc*` runtime imports, proceed to Step 1 (full OpenJDK rebuild). If it CAN, skip Step 1 entirely.
- **Step 1 (OpenJDK)**: separate budgets for environmental vs HybridCRT-related failures.
  - Environmental retries (wrong boot JDK, missing Cygwin packages, MSVC env vars): up to 3 retries. These don't count against the patch budget.
  - HybridCRT-patch-related: 1 configure + 1 make + 1 patch-retry. If still failing, **stop**.
- **Step 2 (GraalVM CE on patched JDK)**: 1 mx build + 1 retry. Stop if still failing.
- **Step 3a (HelloWorld on patched chain)**: 1 attempt. If imports still show `vc*`, capture which compilation unit pulled it in and stop.
- **Step 3b (qodana-cli on patched chain)**: separate budget for native-image class-init retries. If `nativeCompile` fails with "Classes that should be initialized at run time", apply the repo's documented fix path per `CONTRIBUTING.md` (add `--initialize-at-run-time=<class>` in `build-logic/src/main/kotlin/graalvm-native.gradle.kts`) — up to 3 such retries. If qodana-cli builds but the .exe crashes at runtime on a clean Windows host, stop.

User's standing rule: scope decisions are the user's prerogative. If you encounter an unexpected blocker (e.g. the patched JDK boots but breaks on JNI heap interactions), do NOT silently descope; document and ask.

## Out of scope (do not expand)

ARM64 Windows; JDKs other than 25; code signing; performance benchmarking; `qodana-clang` and `qodana-cdnet` modules (their reachability-metadata gap is a separate workstream); Linux musl-static binary work; `qodana-cli scan` end-to-end; production-ready CI pipeline implementation (we discuss it in the writeup, we don't build it); bumping the project-wide Kotlin compilation toolchain (`kotlin-common.gradle.kts`) from 21 to 25 — the spike overrides ONLY the native-image runtime toolchain (Codex's recommendation: don't balloon scope with cross-module migration noise).

---

## Plan

### Pre-flight (Day 0, ~3-5h)

#### Task 0.1: YouTrack ticket + worktree — **DONE** (macOS-side, completed)

- YouTrack ticket: [QD-14840](https://youtrack.jetbrains.com/issue/QD-14840), assignee anna.zhukova, state In progress, linked as "relates to" QD-14812.
- Worktree: `.tmp/claude/worktrees/QD-14840-hybridcrt/` on branch `azhukova/QD-14840`, based on `origin/azhukova/QD-14812`.

**PR #13 fragility check** (do this before each subsequent Step on Windows):
```
gh pr view 13 --json state,mergeStateStatus,headRefOid
```
- If PR #13 has merged: rebase the spike branch onto `main` (`git rebase main`).
- If `headRefOid` differs from the branch's tip when we created the worktree: force-pushed; rebase onto the new tip.

#### Task 0.2: Provision Windows build host (~1h)

Requirements: Windows 10/11 x64 or Server 2019+; ≥16 GB RAM (32 GB recommended); ≥100 GB free disk; VS 2022 Build Tools ("Desktop development with C++" workload); Cygwin (for OpenJDK configure); Git for Windows; Python 3.9+ (for `mx`); 7-zip; Docker Desktop (Server Core test).

Host options (record choice in `notes.md`):
- Azure D8s_v5 VM (~$0.40/hr) — burnable
- Local VM (Parallels/VMware/Hyper-V)
- Internal JetBrains Windows builder if available

NOT suitable: GitHub Actions Windows runners (no persistent state, 6h job limit).

**Checkpoint strategy**: after each successful step (0.7, 1, 2), `7z a checkpoint-<step>.7z <patched-toolchain-tree>` and copy off-host (Azure blob, S3, or USB). Mitigates Azure VM eviction or hardware fault during a multi-day spike.

#### Task 0.3: Read prior art (~1-2h, parallel to 0.2)

- HybridCRT spec: https://github.com/microsoft/WindowsAppSDK/blob/main/docs/Coding-Guidelines/HybridCRT.md — **READ IT VERBATIM**. Copy the exact lib list + `/DEFAULTLIB:`/`/NODEFAULTLIB:` directives into `notes.md`. Do not paraphrase. Key invariant: UCRT must be linked **dynamically** via `ucrt.lib` (import lib → `ucrtbase.dll`). NOT `libucrt.lib` (static UCRT — would defeat the goal).
- OpenJDK Windows build doc: https://github.com/openjdk/jdk/blob/master/doc/building.md (full Windows section).
- GraalVM substratevm build: https://github.com/oracle/graal/blob/master/substratevm/Building.md + https://github.com/oracle/graal/blob/master/vm/README.md. Resolve labsjdk-vs-vanilla-boot-JDK question — paste the relevant paragraph into `notes.md`.
- GraalVM issue #1762: https://github.com/oracle/graal/issues/1762 — every comment, especially `petoncle`'s Dec 2024 comment.
- qodana-kotlin-cli's CONTRIBUTING.md — note the documented fix for `nativeCompile` class-init failures (add `--initialize-at-run-time=<class>` to `build-logic/src/main/kotlin/graalvm-native.gradle.kts`).

#### Task 0.4: Capture baseline (~30 min, Windows host)

With the spike worktree checked out, stock GraalVM 21 CE via foojay:

- First-time foojay download takes 5-10 min — wait for it explicitly before timing the build wall-clock.
- `./gradlew :qodana-cli:nativeCompile` (record wall-clock)
- `dumpbin /imports  qodana-cli\build\native\nativeCompile\qodana-cli.exe   > evidence\baseline-imports.txt`
- `dumpbin /dependents qodana-cli\build\native\nativeCompile\qodana-cli.exe > evidence\baseline-dependents.txt`
- `dir qodana-cli\build\native\nativeCompile\` → record `qodana-cli.exe` size in bytes + which `vcruntime*.dll` files `BundleWindowsCrt` copied alongside
- Save all to `docs/spikes/QD-14840-hybridcrt/evidence/` in the worktree

Confirm: imports include `vcruntime140.dll`, `vcruntime140_1.dll`; bundling step copied 2 DLLs. This is the "before" picture.

#### Task 0.5: Verify buildtools plugin supports JDK 25 (~30 min)

The plugin is pinned at `org.graalvm.buildtools.native = 0.10.6` (`build-logic/gradle/libs.versions.toml:3`). Whether 0.10.6 supports a JDK 25 toolchain is unverified.

- Check release notes: https://github.com/graalvm/native-build-tools/releases — does 0.10.x mention JDK 25?
- If unclear, plan to bump the plugin version (record which) — this is a small, isolated change unlike a Kotlin-side toolchain bump.

#### Task 0.6: Pre-flight the JDK-21-compile + JDK-25-native-image split (~1h)

The plan keeps Kotlin compilation on JDK 21 (preserving current sources) but switches the native-image runtime to JDK 25. This split is unverified. Do an empirical pre-flight:

- Install a vanilla GraalVM CE for JDK 25 (foojay or manual download).
- Set: `-Dorg.gradle.java.installations.paths=<vanilla-graal-25-path>` AND `-Dorg.gradle.java.installations.auto-download=false`.
- Run: `./gradlew :qodana-cli:nativeCompile`
- Expected: build succeeds; produces `qodana-cli.exe`; `qodana-cli.exe --help` works.
- Verify the buildtools plugin actually picks up the GraalVM 25 (not 21 from foojay cache): check the plugin's startup banner / `nativeCompile` log.

If buildtools 0.10.6 refuses to use JDK 25 because `kotlin-common.gradle.kts` pins toolchain to 21: bump the plugin (Task 0.5). If even a newer plugin can't span the 21/25 split: that's a Stop Condition before Step 0.7 — the spike's whole "preserve Kotlin compile on 21" strategy doesn't work; either bump Kotlin to 25 (scope expansion, surface to user) or stop.

Save outputs as `evidence/preflight-split.log`.

#### Task 0.7: GraalVM-only probe (~3-5h) — the risk-reduction step

**The key question**: is the `vcruntime140` dependency introduced (a) by GraalVM's hardcoded `/MD` linker invocation only, (b) by the JDK's static libs only, or (c) by both? If (a), we can skip Step 1 (OpenJDK rebuild — the most expensive step). If (b) or (c), we need both.

Procedure:

1. Clone `oracle/graal` at the JDK-25-aligned branch (see Task 2.1 for branch discovery).
2. Apply ONLY the GraalVM-side HybridCRT patch (Task 2.3 below) — leave OpenJDK alone (use vanilla foojay-downloaded GraalVM CE for JDK 25 from Task 0.6).
3. Install `mx`. Build SubstrateVM against the **vanilla** JDK 25.
4. Use the patched `native-image` to build HelloWorld:
   ```
   echo 'public class HelloWorld { public static void main(String[] a) { System.out.println("Hi"); } }' > HelloWorld.java
   <vanilla-jdk-25>\bin\javac HelloWorld.java
   <patched-native-image>\native-image HelloWorld
   ```
5. `dumpbin /imports HelloWorld.exe > evidence\probe-helloworld-imports.txt`
6. `dumpbin /dependents HelloWorld.exe > evidence\probe-helloworld-dependents.txt`

**Branching decision**:
- If both imports + dependents show NO `vc*` runtime DLLs → **GraalVM-only is sufficient.** Skip Step 1 entirely. Use the vanilla JDK + patched GraalVM in Step 3. The plan's expected effort drops to ~1.5 days.
- If imports DO show `vcruntime140*` → **JDK rebuild is necessary.** Proceed to Step 1.

Record the verdict in `notes.md`. Save the patched `native-image` binary (or its checkpoint tarball) — we'll reuse it in Step 3 either way.

---

### Step 1 (skip if Task 0.7 verdict was "GraalVM-only sufficient"): Patched OpenJDK 25

**Goal**: a JDK 25 build whose `jvm.dll`, `java.dll`, `libnet.dll`, and other JDK-emitted DLLs do NOT import `vcruntime140` or `msvcp140`.

**Budget**: ≤1 working day (1-3h build wall-clock + debug/retry).

#### Task 1.1: Clone JDK 25 update train

- Prefer `https://github.com/openjdk/jdk25u`; fallback: latest `jdk-25.0.x` tag on `openjdk/jdk`
- `git clone --depth=1 -b <tag-or-branch> https://github.com/openjdk/jdk25u.git jdk25u && cd jdk25u`
- `git rev-parse HEAD` — record SHA

#### Task 1.2: Audit the patch surface

Codex's concern: `/MD` may appear in more than the two obvious files. Audit thoroughly:

```
find make/ -name 'flags-cflags.m4' -o -name 'flags-ldflags.m4'   # confirm file paths
git grep -n -- '-MD' make/
git grep -n -- '/MD' make/
git grep -n -- '-MDd' make/   # debug variant
git grep -n -- '-MT' make/    # any conflicting /MT
```

Paste 20-line context around each match into `notes.md`. Patch ALL `/MD` references in the `xmicrosoft` toolchain blocks. If `-MDd` (debug) appears in any release-build path, that's separately suspicious — note it.

#### Task 1.3: Draft the HybridCRT patch

**Read the HybridCRT spec verbatim (Task 0.3) and copy the exact lib selection from there.** This plan does NOT prescribe lib names — earlier drafts had `libucrt.lib` (wrong — static UCRT), and HybridCRT actually requires `ucrt.lib` (import lib for dynamic UCRT) + `libvcruntime.lib` (static) + `libcpmt.lib` (static C++).

Apply to all `/MD` sites identified in Task 1.2, plus the corresponding `flags-ldflags.m4` changes for `/NODEFAULTLIB:` and explicit lib selection.

Save patch to `docs/spikes/QD-14840-hybridcrt/patches/openjdk-jdk25u-hybridcrt.patch` (apply via `git apply`).

#### Task 1.4: Configure

```
bash configure \
    --with-boot-jdk=<vanilla JDK 24 or 25 path> \
    --enable-debug-symbols=external \
    --disable-warnings-as-errors \
    2>&1 | tee configure.log
```

**Environmental retry budget** (up to 3): wrong boot JDK path, missing Cygwin packages, wrong VS env vars. These don't count against patch-quality retries.

#### Task 1.5: `make images`

```
make images 2>&1 | tee make-images.log
```

1-3 hours wall-clock. **Do NOT** poll with `until grep "BUILD SUCCESSFUL"; sleep 5; done` — that's the time-based sync the user's standing rules forbid for processes we control. Pipe to `tee`; let the process exit naturally; inspect log afterward.

**HybridCRT-patch-related retry budget**: 1 make + 1 retry (after patch tweak). If still failing, capture failure context (first failing target, unresolved symbols, file being linked) and **stop**. Do NOT attempt to fix the JDK's own C/C++ source.

#### Task 1.6: Inspect imports of key JDK DLLs

```
dumpbin /imports    build\<config>\images\jdk\bin\server\jvm.dll > evidence\jdk-imports-jvm.txt
dumpbin /imports    build\<config>\images\jdk\bin\java.dll       > evidence\jdk-imports-java.txt
dumpbin /imports    build\<config>\images\jdk\bin\libnet.dll     > evidence\jdk-imports-libnet.txt
dumpbin /dependents build\<config>\images\jdk\bin\server\jvm.dll > evidence\jdk-dependents-jvm.txt
```

Success: NONE reference `vc*140*.dll` or `msvcp*.dll`. Allowed: `ucrtbase.dll`, `api-ms-win-crt-*`, Windows-native.

#### Task 1.7: Smoke test the patched JDK

1. `<patched-jdk>\bin\java -version` — record output (must show our build).
2. HelloWorld: `javac HelloWorld.java && java HelloWorld`.
3. **Mixed-CRT JNI test** (canonical failure mode for HybridCRT done wrong): write a JNI method that calls `fopen` in one translation unit and `fclose` in a separately-compiled .dll. If CRTs aren't unified, this crashes.
4. JEP-466 FFM API sample (new-in-25-vs-21 native surface).
5. Virtual-threads sample.

Note JDK 21 → 25 surface surprises in `notes.md`.

**Checkpoint 1**: patched JDK 25 builds; key DLL imports clean; smoke tests pass.

---

### Step 2 (always): Patched GraalVM CE

**Goal**: a `native-image` binary that respects HybridCRT in its own linker invocation, built atop the chosen JDK (vanilla 25 if Task 0.7 verdict was "GraalVM-only sufficient", else patched 25 from Step 1).

**Budget**: ≤6h (often shorter since Task 0.7 may have already built and patched GraalVM).

#### Task 2.1: Clone oracle/graal

```
gh api -X GET 'repos/oracle/graal/branches' --paginate | jq -r '.[].name' | grep -E '(jdk-?25|release/graal-for-jdk-25)'
```

Pick the latest. Clone with depth=1. Record SHA.

Sanity check before patching: vanilla build against vanilla JDK 25 succeeds.

#### Task 2.2: Audit GraalVM `/MD` references

```
grep -rn '/MD' substratevm/src/
grep -rn 'NODEFAULTLIB:LIBCMT' substratevm/src/
grep -rn 'WindowsCCLinkerInvocation' substratevm/src/
```

Known site: `substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/image/CCLinkerInvocation.java` `WindowsCCLinkerInvocation.setOutputKind`. List all other `/MD` references in `notes.md`; patch each.

#### Task 2.3: Draft the GraalVM patch

Replace the `/MD` `cmd.add(...)` line(s) with the HybridCRT lib selection from Task 0.3's notes.

**Critical** (from the brief): user-provided `-H:CCompilerOption` lands BEFORE hard-coded values. You must REPLACE the `/MD` line, not append — otherwise the static-CRT libs you add get overridden.

Save patch to `docs/spikes/QD-14840-hybridcrt/patches/graal-hybridcrt.patch`.

#### Task 2.4: Build GraalVM

```
git clone --depth=1 https://github.com/graalvm/mx.git && export PATH=$PWD/mx:$PATH
cd graal/substratevm
mx --java-home=<patched JDK from Step 1, OR vanilla JDK 25 if Step 1 skipped> build 2>&1 | tee ../../evidence/graalvm-build.log
```

If labsjdk-import is required per Task 0.3's investigation: apply those steps first.

Stop Condition 2: 1 build + 1 retry. Capture failure (target name, symbols). Stop.

#### Task 2.5: Verify linker invocation

`native-image --version` confirms the build identifier only. For linker behavior, run with verbose tracing:

```
echo 'public class HelloWorld { public static void main(String[] a) { System.out.println("Hi"); } }' > HelloWorld.java
javac HelloWorld.java
native-image --expert-options-detail 2>&1 | grep -iE '(trace|link.*invocation|print.*link)'   # find the right flag
# Likely candidates: -H:+TraceNativeToolUsage, -H:+VerifyNativeLinkerOptions
# Use the discovered flag:
native-image -H:+<DISCOVERED_FLAG> HelloWorld 2>&1 | tee evidence\graalvm-linker-invocation.log
```

Confirm the `link.exe` invocation includes HybridCRT libs (e.g. `libvcruntime.lib`, `ucrt.lib`, `/NODEFAULTLIB:libcmt`).

**Checkpoint 2**: patched GraalVM builds; linker invocation confirmed to include HybridCRT directives.

---

### Step 3: Build qodana-cli.exe with the patched toolchain and verify

#### Task 3.1: HelloWorld smoke

```
<patched-native-image>\native-image HelloWorld
dumpbin /imports    HelloWorld.exe > evidence\helloworld-imports.txt
dumpbin /dependents HelloWorld.exe > evidence\helloworld-dependents.txt
HelloWorld.exe                      # run; should print "Hi"
```

Success: no `vc*` runtime DLLs in imports OR dependents.

If dirty: investigate via `dumpbin /symbols <build-dir>\*.obj | findstr /i vcruntime`. (Note: user-image .obj files are in the image's build dir — e.g. `build/native/nativeCompile/` for Gradle, or `./` for direct `native-image`; NOT in `mxbuild/` which is GraalVM's own output.)

Hit Stop Condition 3a.

#### Task 3.2: Build qodana-cli with patched toolchain (preferred path)

**Preferred: buildtools-plugin path (no Gradle edits to `kotlin-common.gradle.kts`).**

- Set `-Dorg.gradle.java.installations.paths=<patched-graalvm-path>;<patched-jdk-path-or-empty>` (semicolon-separated on Windows; comma on Unix)
- Set `-Dorg.gradle.java.installations.auto-download=false`
- Verify Task 0.6 pre-flight succeeded — buildtools plugin can use the JDK 25 toolchain without Kotlin-side bump.
- Run: `./gradlew :qodana-cli:nativeCompile`

Class-init retry mini-loop (per CONTRIBUTING.md): if `nativeCompile` fails with "Classes that should be initialized at run time", add `--initialize-at-run-time=<class>` to the `buildArgs` list in `build-logic/src/main/kotlin/graalvm-native.gradle.kts`. Commit each addition on the spike branch (transparent change). Budget: up to 3 such retries.

**Fallback only if buildtools plugin truly cannot consume the patched toolchain**: surface to user. Do NOT attempt the raw `native-image -cp ... -H:ConfigurationFileDirectories=...` invocation — Codex confirmed the buildtools plugin uses `metadataRepository { enabled.set(true) }`, which downloads upstream reachability metadata that's NOT committed under `src/main/resources`. A raw `native-image` call would miss those downloaded configs and likely produce `MissingReflectionRegistrationError` at runtime — easily misdiagnosed as a HybridCRT failure.

Whichever path works, produce `qodana-cli\build\native\nativeCompile\qodana-cli.exe`.

#### Task 3.3: Inspect imports + binary size

```
dumpbin /imports    qodana-cli\build\native\nativeCompile\qodana-cli.exe > evidence\qodana-cli-imports.txt
dumpbin /dependents qodana-cli\build\native\nativeCompile\qodana-cli.exe > evidence\qodana-cli-dependents.txt
dir qodana-cli\build\native\nativeCompile\qodana-cli.exe   # capture size
```

Apply regex check (from Goal point 1): `(?i)(vcruntime|msvcp|concrt|msvcr|vcomp|mfc|mfcm|vcamp|vccorlib|atl)[0-9_]*\.dll` — must NOT match anything in the imports/dependents output.

Record size; compare to baseline (Task 0.4). Static CRT linking inflates the .exe — quantify the delta for the writeup.

#### Task 3.4: Cross-check dumpbin vs PortEx

PortEx and dumpbin may disagree (especially on delay-loads). Use the script committed at `docs/spikes/QD-14840-hybridcrt/scripts/check-imports.main.kts`:

```
kotlin docs/spikes/QD-14840-hybridcrt/scripts/check-imports.main.kts qodana-cli\build\native\nativeCompile\qodana-cli.exe > evidence\qodana-cli-portex-imports.txt
```

Confirm the union of `qodana-cli-imports.txt` + `qodana-cli-dependents.txt` equals `qodana-cli-portex-imports.txt`. Any discrepancy: investigate before claiming success.

**The PortEx output is the authoritative source** since the existing CI infrastructure parses with PortEx.

#### Task 3.5: Run on clean Windows host

```
docker run --rm --isolation=process --network none -v "<spike-dir>:C:\test" mcr.microsoft.com/windows/servercore:ltsc2022 cmd /c "C:\test\qodana-cli.exe --help"
```

(Note: `--isolation=process` matches what JetBrains customers run in CI, not the default Hyper-V isolation.)

Plus: same `--help` on a fresh Windows VM with no VC++ Redistributable.

Save outputs to `evidence/runtime-{servercore,clean-vm}.txt`. Capture stdout, stderr, exit code, host OS build.

Success: exits 0 with help text; no "VCRUNTIME140.dll was not found" error.

If imports were clean but binary crashes: Stop Condition 3b. Mixed-CRT semantics is the prime suspect. Capture event log, stack if any.

#### Task 3.6 (informational): Run NativeWindowsDepsTest

```
./gradlew :qodana-cli:test --tests "*NativeWindowsDepsTest" -PnativeTests=true
```

Expected: PASS. With clean imports, the test passes whether or not `BundleWindowsCrt` copied DLLs alongside — the assertion is "imports ≤ bundled DLLs", and with imports empty of `vc*`, that's vacuously satisfied.

**This is informational only** — it confirms we haven't regressed Phase A's regression-detection, but it does NOT prove HybridCRT works. The load-bearing proof is Task 3.3 + Task 3.4 + Task 3.5 (clean dumpbin/PortEx imports + runs on clean Windows host).

If the test FAILS: cross-reference the dumpbin/PortEx output. The test's regex (PR #13's `VC_RUNTIME_REGEX`) matches `vcruntime|msvcp|concrt|msvcr|vcomp|mfc|mfcm|vcamp|atl` — a subset of our broader regex. If the test catches something we missed (e.g. `vccorlib140`): update our regex and re-evaluate.

**Checkpoint 3**: qodana-cli.exe has clean imports (dumpbin + dependents + PortEx all agree); runs `--help` on Server Core (process isolation) + clean VM; size delta vs baseline recorded. **This is the spike's success.**

---

### Step 4: Brief writeup (~1h)

Three artifacts:

1. **PR description** (3-5 paragraphs on the spike branch's draft PR):
   - Verdict (1 line: feasible / infeasible / feasible-with-caveats)
   - Task 0.7 outcome: GraalVM-only sufficient, or full OpenJDK rebuild needed?
   - Patch summary (link to patch files)
   - Evidence summary (links to dumpbin/PortEx outputs, runtime logs)
   - Build time per step (actual, vs the targets in this plan)
   - Discussion topics (below)
2. **YouTrack QD-14840 comment**: same verdict + link to PR + the `qodana-cli.exe` attached as a binary.
3. **Worktree contents**: patches under `patches/`, evidence under `evidence/`, scripts under `scripts/`, `notes.md` with real-time observations.

**Topics for free-form discussion**:
- Vendoring strategy: forks vs patches-in-repo
- CI cost: cold build wall-clock (1.5-4h); GitHub Actions cache 10 GB cap; cache key (hash of patches + upstream SHAs)
- License obligations: OpenJDK GPL-2.0+CE + GraalVM EPL-2.0/GPL-2.0+CE — ship modified source (public fork satisfies); `qodana-cli.exe` doesn't inherit GPL (Classpath Exception)
- **Security-update cadence**: without VC++ Redistributable, we lose Windows Update's automatic CRT security patches. We need rebuild discipline when MSVC ships CRT fixes
- **Binary size delta**: static CRT inflation (measured)
- Update cadence: per-JDK-25.0.x and per-GraalVM-26 porting cost
- **JDK 21 → 25 migration risks** observed in Task 1.7 smoke tests (if Step 1 ran)
- **`qodana-clang` and `qodana-cdnet` reachability-metadata gap**: separate workstream
- **Reusable Gradle mechanism for custom GraalVM**: if spike succeeds, repo benefits from a `-Pcustom-graalvm=<path>` hook in `graalvm-native.gradle.kts`
- Linux `.so` clarification: not a problem for glibc-based distros; would become one for Alpine/musl — separate musl-static workstream if needed

### Verification — spike "ready for review"

Per CLAUDE.local.md workflow rule:

1. Branch `azhukova/QD-14840` pushed (based on `azhukova/QD-14812`).
2. Draft PR opened against `azhukova/QD-14812` (or `main` once PR #13 merges), containing: patches, evidence files, `notes.md`, writeup in PR description. No `.exe` committed.
3. GitHub Actions CI green. **Caveat to record in PR description**: GHA's native-compile job uses vanilla foojay-downloaded GraalVM 21, NOT our patched toolchain (per Task 0.2: GHA Windows runners can't build the patched .exe in <6h). CI green proves "no regression to existing test infrastructure", NOT "spike's .exe is valid". The actual spike evidence is the human-driven verification (Task 3.3-3.5).
4. YouTrack ticket QD-14840 updated with: link to PR, one-line verdict, `qodana-cli.exe` attached.
5. Demonstrated: `qodana-cli.exe --help` runs on Server Core (process-isolation) + clean VM.

---

## Time tracking (record in `notes.md`)

Targets:
- Task 0 (setup + pre-flight + GraalVM-only probe): 3-7h depending on probe outcome
- Step 1 (OpenJDK rebuild, conditional): ≤8h or 0h (skipped)
- Step 2 (GraalVM): ≤6h (often shorter since Task 0.7 may have done most of it)
- Step 3 (qodana-cli + verify): ≤4h
- Step 4 (writeup): 1h
- **Best case** (probe succeeds, skip Step 1): ~1.5 working days
- **Full path** (probe says need OpenJDK rebuild): ~3-4 working days

If any individual step blows its budget by 50%, hit the corresponding Stop Condition.

## Anti-patterns (reinforcing user's standing rules)

- **Don't** poll-with-sleep waiting for builds. Pipe to `tee` log file; let the build process exit naturally; inspect log afterward. (Wall-clock waits for external state — e.g. a CI run we don't control — are the exception, but spike builds are processes we spawn.)
- **Don't** fall back to DLL bundling silently if HybridCRT fails. User explicitly rejected silent fallback.
- **Don't** try to fix the JDK's own C/C++ source to be `/MT`-clean. Out of scope; surface to user.
- **Don't** add scope. No ARM64, no other native modules, no Linux musl-static, no codesigning, no `qodana-cli scan` invocation. Flag in writeup; don't implement.
- **Don't** bump `kotlin-common.gradle.kts` to JDK 25. Codex's recommendation: only override the native-image runtime, keep Kotlin compile on 21. If that doesn't work, surface to user before scope-ballooning.
- **Don't** delete bundled DLLs between `nativeCompile` and the test — TOCTOU race; Phase A test is informational only anyway. Load-bearing proof is dumpbin/PortEx + runtime.
- **Don't** trust this plan's mention of specific HybridCRT lib names. Read the spec verbatim.
- **Don't** use `cd /repo/root && command` — reset CWD first per user's command rule.
- **Don't** use `curl` without explicit `-X GET`; `gh api` without `-X GET`; `docker run` without `--rm` + appropriate network/isolation flags.

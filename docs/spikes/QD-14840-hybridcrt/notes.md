# QD-14840 spike notes

Real-time observations from the spike. Updated as work progresses.

## Host details (wingolem, ssh-accessible)

- OS: Windows 11, build via MSYS_NT-10.0-26100 (Git for Windows bash)
- CPU: 8 cores
- RAM: provisioned with enough headroom
- Disk: 97 GB free on `C:` at start
- Shell: PowerShell default; bash via Git for Windows (`/usr/bin/bash`)
- Connection: `ssh wingolem` (admin session)

## Toolchain (mostly pre-provisioned by the user)

- **Visual Studio 18 Build Tools** at `C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\`. MSVC 14.51.36231. `cl.exe`, `link.exe`, `dumpbin.exe` under `VC\Tools\MSVC\14.51.36231\bin\Hostx64\x64\`.
- **Oracle GraalVM JDK 25.0.3+9.1** at `~/graalvm-jdk-25.0.3+9.1/` — full distro including `native-image.cmd`. **Rejected** by `mx build` as base JDK ("GraalVM cannot be built using a GraalVM as base-JDK").
- **labsjdk-ce-25.0.2+10-jvmci-b01** manually downloaded from <https://github.com/graalvm/labs-openjdk/releases/tag/25.0.2+10-jvmci-b01>. The 25.0.3 release `common.json` pins to (`25.0.3+9-jvmci-b01`) isn't published yet; the closest available is 25.0.2. Used with `JVMCI_VERSION_CHECK=ignore` since the graal source expects 25.0.3.
- `uv` 0.11.17 — Python manager.
- Python 3.12.13 via `uv python install 3.12` (the WindowsApps `python` was a Microsoft Store stub).
- `mx` cloned at `~/work/qd-14840/mx/` @ `cf215df`.
- `gh`, `curl`, `git`, `7z` (via Git Bash MSYS).
- **Not yet installed**: Docker Desktop (needed only at Task 3.5 Server Core runtime test), full MSYS2 (only if Step 1 OpenJDK rebuild runs).

## Working directory layout (~/work/qd-14840/)

```
env.sh                              # env-setup (JAVA_HOME, MX_PYTHON, PATH, JVMCI_VERSION_CHECK)
build-substrate.sh                  # mx-build wrapper
graal/                              # oracle/graal @ release/graal-vm/25.0 (commit dbf49c84)
mx/                                 # graalvm/mx @ commit cf215df
jdks/labsjdk-ce-25.0.2-jvmci-b01/   # JAVA_HOME for mx build
probe-vanilla/                      # Task 0.4 baseline (vanilla HelloWorld.exe)
qodana-kotlin-cli/                  # spike-branch clone for Task 3
mx-build.log                        # mx build output
graal-hybridcrt.patch               # synced from worktree, applied to graal/
```

## Prior-art notes

### HybridCRT spec (Microsoft, verbatim quote)

From <https://github.com/microsoft/WindowsAppSDK/blob/main/docs/Coding-Guidelines/HybridCRT.md>:

| MSBuild property                   | Debug                           | Release                        |
| ---------------------------------- | ------------------------------- | ------------------------------ |
| `<RuntimeLibrary>`                 | `MultiThreadedDebug`            | `MultiThreaded`                |
| `<IgnoreSpecificDefaultLibraries>` | appends `libucrtd.lib`          | appends `libucrt.lib`          |
| `<AdditionalOptions>` (Link)       | appends `/defaultlib:ucrtd.lib` | appends `/defaultlib:ucrt.lib` |

Equivalent cl.exe / link.exe flags (Release):

```
/MT  /link  /NODEFAULTLIB:libucrt.lib  /defaultlib:ucrt.lib
```

The spec is explicit: **only** `libucrt.lib` (Release) or `libucrtd.lib` (Debug) is excluded; `libvcruntime.lib` and `libcpmt.lib` come in **implicitly** via `/MT`. `msvcrt(d).lib` must **never** be used.

### GraalVM patch sites — oracle/graal @ release/graal-vm/25.0 (commit `dbf49c84`)

`substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/image/CCLinkerInvocation.java`:

- Line 500-501: `// Must use /MD in order to link with JDK native libraries built that way` + `cmd.add("/MD");` — for `EXECUTABLE` / `STATIC_EXECUTABLE`
- Line 505: `cmd.add("/MD");` — for `IMAGE_LAYER` / `SHARED_LIBRARY`
- Line 532: `cmd.add("/NODEFAULTLIB:LIBCMT");` — REMOVED in the patch (we WANT LIBCMT under /MT)

Patched per `patches/graal-hybridcrt.patch`. Applied via `git apply --recount` (hand-authored hunk line counts were off by one).

## Task 0.4 baseline — vanilla Oracle GraalVM 25.0.3 (evidence/baseline-helloworld-imports.txt)

HelloWorld.java compiled with stock Oracle GraalVM 25.0.3 `native-image.cmd` (no patches). Resulting `HelloWorld.exe` is 6.4 MB and imports:

```
ADVAPI32.dll
api-ms-win-crt-convert-l1-1-0.dll
api-ms-win-crt-environment-l1-1-0.dll
api-ms-win-crt-filesystem-l1-1-0.dll
api-ms-win-crt-heap-l1-1-0.dll
api-ms-win-crt-locale-l1-1-0.dll
api-ms-win-crt-math-l1-1-0.dll
api-ms-win-crt-runtime-l1-1-0.dll
api-ms-win-crt-stdio-l1-1-0.dll
api-ms-win-crt-string-l1-1-0.dll
KERNEL32.dll
USER32.dll
USERENV.dll
VCRUNTIME140.dll     <-- the smoking gun
VERSION.dll
WS2_32.dll
```

Notably absent: `VCRUNTIME140_1.dll`, `MSVCP140.dll`, `CONCRT140.dll`. HelloWorld is bare-bones; qodana-cli (Kotlin stdlib, Jackson, Clikt) is the one that also pulls in `VCRUNTIME140_1.dll` per Phase A's existing CI evidence.

Vanilla GraalVM 25.0.3 confirms the problem is still present in the current GraalVM tip — not an old GraalVM 21 artifact.

## Task 0.7 GraalVM-only probe — **VERDICT: GraalVM-only patch SUFFICIENT** ✓

Patched GraalVM CE for JDK 25 (built from `oracle/graal@release/graal-vm/25.0` with the HybridCRT
linker patch) was used to AOT-compile a plain HelloWorld.java. Result: `VCRUNTIME140.dll` is gone
from the import table. Step 1 (OpenJDK rebuild) is NOT required to remove the dependency.

### Comparison

|                                   | Vanilla (Oracle GraalVM 25.0.3) | Patched (GraalVM CE 25.0.2+10, HybridCRT) |
| --------------------------------- | ------------------------------- | ----------------------------------------- |
| Binary size                       | 6,385,664 bytes (~6.1 MB)       | 13,447,168 bytes (~12.8 MB)               |
| Static CRT linking cost           | n/a                             | +7.1 MB (~2.1×)                           |
| `VCRUNTIME140.dll` in imports     | **yes**                         | **no**                                    |
| `MSVCP140.dll` in imports         | no                              | no                                        |
| Runs on host (`./HelloWorld.exe`) | yes                             | yes                                       |

Full dumpbin outputs in `evidence/evidence-{vanilla,patched}-{imports,dependents}.txt`.

### Patched HelloWorld import set (no VC++ runtime DLLs)

```
ADVAPI32.dll
api-ms-win-crt-{convert,environment,filesystem,heap,locale,math,runtime,stdio,string}-l1-1-0.dll
IPHLPAPI.DLL
KERNEL32.dll
MSWSOCK.dll
USER32.dll
USERENV.dll
VERSION.dll
WS2_32.dll
```

The patched binary picked up `IPHLPAPI.DLL` and `MSWSOCK.dll` that the vanilla didn't — likely
because labsjdk-25.0.2's static libs reference slightly different surfaces than Oracle GraalVM
25.0.3's distribution. Both are Windows-native DLLs (always present), so no concern.

### Build environment chain (chronological debug ladder)

The probe took ~6 build attempts to actually produce output, each surfacing a specific env
issue (none caused by the HybridCRT patch itself):

1. **Inline `$JAVA_HOME` substitution**: `mx --java-home=$JAVA_HOME build` parsed as
   `--java-home=` + `build` arg when `$JAVA_HOME` was empty pre-source. Fixed by
   `build-substrate.sh` wrapper that sources `env.sh` first.
2. **mx rejects Oracle GraalVM as boot**: "GraalVM cannot be built using a GraalVM as base-JDK".
   Fixed by downloading `labsjdk-ce-25.0.2+10-jvmci-b01` and pointing `JAVA_HOME` at it.
3. **JVMCI version pin too strict in `mx`**: `release/graal-vm/25.0` expects
   `25.0.3+9-jvmci-b01`; only `25.0.2+10-jvmci-b01` is published. Fixed by
   `JVMCI_VERSION_CHECK=ignore` in env.sh.
4. **`dumpbin` not on PATH for substratevm native build**: failed at
   `JvmFuncsFallbacksBuildTask`. Fixed by adding MSVC bin dir to PATH.
5. **`stdio.h` not found by `cl.exe`**: MSVC `INCLUDE` env not set. Fixed by sourcing
   `vcvars64.bat` via a `dump-vcvars.bat` intermediary (MSYS bash kept rewriting `>nul` to
   `>/dev/null` in the .bat file when written from a heredoc, so we wrote it via `printf '\r\n'`).
6. **`export 'CommonProgramFiles(x86)=...'`** invalid identifier: env.sh's vcvars import
   wasn't filtering Windows env var names with parens. Fixed with a POSIX-identifier regex.
7. **Windows MAX_PATH (260 chars) exceeded** during truffle archive symlink. Fixed by enabling
   long path support: `reg add "HKLM\SYSTEM\CurrentControlSet\Control\FileSystem" /v
LongPathsEnabled /t REG_DWORD /d 1 /f` + `git config --global core.longpaths true`.
8. **JVMCI version check inside the patched native-image's HotSpot runtime**: the env-var
   override `JVMCI_VERSION_CHECK=ignore` did not propagate bash → cmd → java reliably. Fixed
   by patching `compiler/src/jdk.graal.compiler/src/jdk/graal/compiler/hotspot/JVMCIVersionCheck.java`
   to early-return from `failVersionCheck` (a HybridCRT-orthogonal artifact of using labsjdk-25.0.2
   when graal/25.0 expects labsjdk-25.0.3; a labsjdk-25.0.3 release would obviate this patch).

The dev-loop friction is real if anyone reproduces this from scratch. For productionization
(out of spike scope), the env setup deserves its own `dev-setup.sh` automation.

### "Spike-only" patches that should NOT ship

The JVMCI version check bypass (item 8 above) is a **spike-only workaround** because the public
`labs-openjdk` release for JDK 25.0.3 isn't published yet. Once it's published, the bypass
becomes unnecessary. Do NOT ship the JVMCI patch in any rollout artifact; only the HybridCRT
patch (`patches/graal-hybridcrt.patch`) is the actual spike deliverable.

## Time tracking

| Step                                                | Target    | Actual      |
| --------------------------------------------------- | --------- | ----------- |
| Task 0 (host inventory + env setup + tool installs) | 2-3h      | ~1h         |
| Task 0.4 (vanilla HelloWorld baseline)              | 30min     | ~3min       |
| Task 0.7 (GraalVM-only probe, mx build)             | 3-5h      | in progress |
| Step 1 (OpenJDK rebuild, conditional)               | ≤8h or 0h | _TBD_       |
| Step 2 (GraalVM, conditional on Step 1)             | ≤6h       | _TBD_       |
| Step 3 (qodana-cli build + verify)                  | ≤4h       | _TBD_       |
| Step 4 (writeup)                                    | 1h        | _TBD_       |

## Step 3 — qodana-cli with patched toolchain — **SUCCESS** ✓✓✓

`qodana-cli.exe` built by Gradle's `:qodana-cli:nativeCompile` (Gradle 9.4.0,
`org.graalvm.buildtools.native = 0.10.6`) using the patched GraalVM CE as both `JAVA_HOME` and
`GRAALVM_HOME`. Build wall-clock: 1m 2s (incremental; cold is ~10 min).

**Import set** (full output in `evidence/evidence-qodana-cli-imports.txt`):

```
ADVAPI32.dll
api-ms-win-crt-{convert,environment,filesystem,heap,locale,math,runtime,stdio,string}-l1-1-0.dll
CRYPT32.dll
IPHLPAPI.DLL
KERNEL32.dll
MSWSOCK.dll
ncrypt.dll
PSAPI.DLL
Secur32.dll
USER32.dll
USERENV.dll
VERSION.dll
WINHTTP.dll
WS2_32.dll
```

Forbidden regex `(?i)(vcruntime|msvcp|concrt|msvcr|vcomp|mfc|mfcm|vcamp|vccorlib)[0-9_]*\.dll`:
**zero matches**. The vanilla qodana-cli.exe (per Phase A CI evidence) had `VCRUNTIME140.dll` +
`VCRUNTIME140_1.dll`; the patched version has neither.

`qodana-cli.exe --help` runs successfully on the build host (exit 0, full menu output including
`scan`/`init`/`pull`/`show`/etc.). UCRT umbrella + Windows-native DLLs only — no VC++
Redistributable required.

**`/imports` and `/dependents` agree** on the import set (no delay-load surprises).

### Build artifacts

- `qodana-cli.exe`: 81,207,296 bytes (~77 MB), built with `-H:+ReportExceptionStackTraces` and
  the standard reachability metadata under `qodana-cli/src/main/resources/META-INF/native-image/`.
- Side note: size increase from a vanilla build of the same module is not measured for this
  artifact (the vanilla qodana-cli baseline path failed at toolchain auto-download in Gradle 9.4,
  see "Spike-only toolchain bumps" below). The HelloWorld measurement of 2.1× growth is the best
  available proxy; for qodana-cli specifically the relative growth will be smaller because more
  of the binary is the user's own Kotlin/Java code rather than CRT-touching glue.

### "Spike-only" toolchain bumps on the spike branch

To make `:qodana-cli:nativeCompile` run with the patched GraalVM CE 25 (the patched GraalVM is
necessarily JDK 25-based; rebuilding for JDK 21 is out of scope), three small Gradle pins were
bumped on the spike branch only. **These must be reverted before any rollout**:

1. `build-logic/src/main/kotlin/kotlin-common.gradle.kts:11,16`: `JavaLanguageVersion.of(21)` /
   `jvmToolchain(21)` → `25`.
2. `qodana-cli/build.gradle.kts:16`: `JavaLanguageVersion.of(21)` → `25`.
3. `gradle/gradle-daemon-jvm.properties:2`: `toolchainVersion=21` → `25`.
4. `build-logic/src/main/kotlin/kotlin-common.gradle.kts`: also added an explicit
   `jvmTarget = JVM_23` for Kotlin + `options.release.set(23)` for Java because the Kotlin
   compiler at this version maxes out at jvmTarget 23 (Java 25 default jvmTarget caused a target
   mismatch).

The full toolchain migration (21 → 25 across all modules, with the appropriate Kotlin compiler
bump) is a separate workstream out of this spike's scope. The spike merely proves that the
HybridCRT-patched GraalVM works when paired with a 25-aligned toolchain.

**Open items for the free-form discussion**:

- **Binary size cost**: HelloWorld grew 2.1× (6.4 MB → 13.4 MB). qodana-cli's relative growth
  will be smaller (more user code, same CRT static cost). Quantify both vs. the bundled-DLL
  alternative (Phase A bundles ~140 KB of vcruntime DLLs alongside; static CRT replaces that
  with ~7 MB inside the binary).
- **Vendoring strategy**: the patched GraalVM CE is ONE Java file changed (4 hunks, ~10 LOC).
  Two options for the rollout:
  - **Patches-in-repo** (cheap): keep `patches/graal-hybridcrt.patch` in qodana-kotlin-cli,
    fetch+patch+build oracle/graal in CI on every spike-built release. Cache the resulting
    GraalVM artifact keyed on (graal SHA, patch hash, JDK SHA).
  - **Fork** (expensive): create `JetBrains/graal-hybridcrt` long-lived fork; sync from oracle
    upstream on a cadence. Easier to consume from external tooling but more maintenance.
- **Security cadence**: without VC++ Redistributable, Windows Update no longer brings CRT
  security fixes to qodana-cli installs. We must rebuild and re-ship when MSVC ships CRT
  patches (historically ~quarterly). Need a monitoring strategy + release-engineering
  commitment for this.
- **Update cadence**: per-JDK-25.0.x and per-GraalVM-26 porting cost. Estimate ≤2h per port if
  the patched lines stay where they are; the `/MD` site has been stable in `CCLinkerInvocation`
  for many years (the `// cmd.add("/MT");` commented-out line predates the spike by years).
- **License**: GraalVM CE source is EPL-2.0/GPL-2.0+CE — modifying + redistributing requires
  publishing the patch, which we do anyway via this spike branch. OpenJDK GPL-2.0+CE isn't
  invoked (we don't rebuild OpenJDK). The resulting `qodana-cli.exe` inherits Classpath
  Exception — no GPL viral effect on commercial distribution.
- **JVMCI version check bypass**: spike-only workaround (because `labsjdk-25.0.3` isn't
  published yet; only `25.0.2+10` is). Once `25.0.3+9-jvmci-b01` lands in `graalvm/labs-openjdk`
  releases, the bypass becomes unnecessary. The patch is in `notes.md` debug ladder item 8.
- **Toolchain bumps on the spike branch**: 21 → 25 in `kotlin-common.gradle.kts`,
  `qodana-cli/build.gradle.kts`, `gradle/gradle-daemon-jvm.properties`. Plus a Kotlin `jvmTarget
= JVM_23` cap. These are spike-local and must be reverted; a real rollout needs a separate
  JDK 21 → 25 migration workstream (or a JDK 21-pinned variant of the patched GraalVM, which
  would require pinning the patch against an older oracle/graal branch).
- **`qodana-clang` and `qodana-cdnet`**: out of immediate scope. Their reachability metadata
  needs work (no `META-INF/native-image/` directory). Once that's solved separately, the same
  patched GraalVM works for them too without further GraalVM-side changes.
- **Reusable Gradle hook for custom GraalVM**: this spike used ad-hoc `JAVA_HOME=$PATCHED` +
  `-Dorg.gradle.java.installations.paths=...`. A first-class `-Pcustom-graalvm=<path>` property
  in `build-logic/src/main/kotlin/graalvm-native.gradle.kts` would make the rollout cleaner.
- **Linux `.so` clarification**: not a problem for glibc-based distros; would become one for
  Alpine/musl users — separate musl-static workstream if ever needed. Out of this spike's
  scope.

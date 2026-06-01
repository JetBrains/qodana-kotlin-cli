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

## Task 0.7 GraalVM-only probe — in progress

`~/work/qd-14840/build-substrate.sh` builds patched SubstrateVM via `mx build` atop `labsjdk-ce`.

### Earlier failed attempts (env, not the patch)

1. **Inline `$JAVA_HOME` substitution**: `mx --java-home=$JAVA_HOME build` parsed as `--java-home=` + `build` arg when `$JAVA_HOME` was empty pre-source. Fixed by `build-substrate.sh` wrapper that sources `env.sh` first.
2. **mx rejects Oracle GraalVM as boot**: "GraalVM cannot be built using a GraalVM as base-JDK". Fixed by switching to labsjdk-ce.
3. **JVMCI version pin too strict**: `release/graal-vm/25.0` expects `25.0.3+9-jvmci-b01`; only `25.0.2+10-jvmci-b01` is published in `graalvm/labs-openjdk`. Fixed by setting `JVMCI_VERSION_CHECK=ignore`.

### Branching decision (Task 0.7 verdict)

- If patched HelloWorld imports have NO `vc*` runtime DLLs → **GraalVM-only sufficient**, skip Step 1
- If imports STILL show `VCRUNTIME140*` → **JDK rebuild necessary**, proceed to Step 1

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

## Verdict (Step 4)

_TBD pending Task 0.7 results._

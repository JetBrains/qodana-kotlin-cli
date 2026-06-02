# Runtime proof: qodana-cli.exe runs with `vcruntime140*.dll` physically absent

The import-table evidence (`dumpbin /imports` + `/dependents` + PortEx) already shows the patched
`qodana-cli.exe` references no VC++ runtime DLL. This test closes the loop empirically: it removes
`vcruntime140.dll` and `vcruntime140_1.dll` from the host and shows the binary still runs — while a
**control** binary that _does_ import `VCRUNTIME140.dll` fails in the same instant.

## Why a controlled experiment (not just "run it on the host")

The build host is **Windows 11 IoT Enterprise LTSC** but has the VC++ runtime present (installed
as a side effect of VS Build Tools). Simply running `qodana-cli.exe --help` on the host proves
nothing, because vcruntime140 _is_ available. So we make it genuinely absent and compare two
binaries head-to-head.

## Method

On the host (`ssh wingolem`), in a single guarded script (full restore via `trap`):

1. Set `HKLM\SYSTEM\CurrentControlSet\Control\Windows\ErrorMode = 2` so a missing-DLL failure
   returns an exit code instead of popping a modal dialog (which would hang a headless run).
2. Rename `C:\Windows\System32\vcruntime140.dll` and `vcruntime140_1.dll` out of the way
   (`*.qd14840bak`). `ucrtbase.dll` and the `api-ms-win-crt-*` apiset forwarders stay in place.
3. Run two binaries from a neutral temp dir with a **scrubbed PATH**
   (`C:\Windows\System32;C:\Windows;C:\Windows\System32\Wbem` only — crucially excluding the
   patched GraalVM `bin/`, which itself ships a `vcruntime140.dll` that would otherwise satisfy
   the loader and invalidate the test):
   - **control** = the vanilla-GraalVM `HelloWorld.exe` (imports `VCRUNTIME140.dll`)
   - **treatment** = the patched `qodana-cli.exe`
4. Restore both DLLs and the `ErrorMode` value (verified present at correct sizes afterward).

Both binaries import the same `api-ms-win-crt-*` apiset DLLs, so the apiset is held constant; the
**only** difference between them is the `VCRUNTIME140` import.

## Result — run A (launched via MSYS `env`)

```
=== System32 vcruntime140* now ABSENT ===
(only vcruntime140_clr0400.dll, vcruntime140_threads.dll, *d.dll variants remain —
 NOT the bare vcruntime140.dll / vcruntime140_1.dll)

CONTROL: vanilla HelloWorld.exe  -> exit 127
  control output:
  error while loading shared libraries: api-ms-win-crt-locale-l1-1-0.dll:
  cannot open shared object file: No such file or directory

TREATMENT: patched qodana-cli.exe --help -> exit 0
  New version of qodana CLI is available: 2026.1.1. ...
  Usage: qodana [<options>] <command> [<args>]...
  ... (full Qodana CLI menu) ...
```

The control's error text named `api-ms-win-crt-locale-l1-1-0.dll` rather than `vcruntime140.dll` —
an MSYS-launcher reporting quirk. It does not weaken the conclusion: **the treatment imports that
exact same apiset DLL and loaded it fine**, so the apiset was present and working; the only thing
that could make the control fail and the treatment succeed is the `VCRUNTIME140` import.

## Result — run B (launched via native `cmd.exe`, removes the MSYS layer)

```
CONTROL  (vanilla HelloWorld.exe):  CONTROL_EXITCODE=-1073741515
TREATMENT (patched qodana-cli.exe --help):  full Qodana CLI menu printed, exit 0
```

`-1073741515` = `0xC0000135` = **`STATUS_DLL_NOT_FOUND`** — the canonical native Windows loader
status for "a required DLL was not found." This is the native loader naming the exact failure
mode: the control cannot start because `VCRUNTIME140.dll` is gone.

## Conclusion

In an environment where `vcruntime140.dll` and `vcruntime140_1.dll` are physically absent:

| Binary                               | Imports `VCRUNTIME140`? | Result                                          |
| ------------------------------------ | ----------------------- | ----------------------------------------------- |
| vanilla `HelloWorld.exe` (control)   | yes                     | **fails** — `STATUS_DLL_NOT_FOUND` (0xC0000135) |
| patched `qodana-cli.exe` (treatment) | no                      | **runs** — `--help` exits 0 with full output    |

This is the runtime confirmation of the spike's success criterion #2: the patched `qodana-cli.exe`
does not need the Microsoft VC++ Redistributable. Reproduction launchers:
`scripts/run-control.bat`, `scripts/run-treatment.bat`.

Host left clean afterward: both System32 DLLs restored to original sizes (178 848 and 50 256
bytes), no `.qd14840bak` leftovers, `ErrorMode` value removed.

# QD-14840 spike notes

Real-time observations from the executor on the Windows build host. Update as you go.

## Host details

- OS build: _TODO_
- CPU: _TODO_
- RAM: _TODO_
- Free disk: _TODO_
- VS 2022 Build Tools version: _TODO_
- Cygwin version: _TODO_
- Python version (for mx): _TODO_

## Repos cloned (record SHAs)

- jdk25u: _TODO_ (commit SHA, recorded only if Step 1 runs)
- oracle/graal at JDK-25 branch: _TODO_ (branch name + commit SHA)
- mx: _TODO_

## Prior-art notes

### HybridCRT spec — exact lib list (from https://github.com/microsoft/WindowsAppSDK/blob/main/docs/Coding-Guidelines/HybridCRT.md)

_TODO: copy verbatim from the spec — do not paraphrase._

### GraalVM labsjdk vs vanilla JDK 25 as boot — decision

_TODO: paste relevant paragraph from substratevm/Building.md; record decision._

### GraalVM issue #1762 — relevant takeaways

_TODO: especially petoncle's Dec 2024 comment on vcruntime140_1.dll quirks._

## Buildtools plugin JDK 25 compatibility (Task 0.5)

_TODO: result of release-notes check + empirical pre-flight._

## JDK 21/25 split pre-flight (Task 0.6)

_TODO: result of `./gradlew :qodana-cli:nativeCompile` with vanilla GraalVM 25._

## Task 0.7 probe verdict

_TODO: GraalVM-only sufficient (skip Step 1), or full chain needed (proceed to Step 1)?_

## JDK 21 → 25 migration risks observed (Task 1.7, if Step 1 runs)

_TODO: list any API/behavior surprises._

## /MD audit results (Task 1.2 + Task 2.2)

_TODO: full list of /MD references found, with file paths and line numbers._

## Time tracking

| Step | Target | Actual |
|------|--------|--------|
| Task 0 (setup) | 2-3h | _TODO_ |
| Task 0.7 (probe) | 3-5h | _TODO_ |
| Step 1 (OpenJDK, conditional) | ≤8h or 0h | _TODO_ |
| Step 2 (GraalVM) | ≤6h | _TODO_ |
| Step 3 (qodana-cli + verify) | ≤4h | _TODO_ |
| Step 4 (writeup) | 1h | _TODO_ |
| **Total** | 1.5-4 days | _TODO_ |

## Verdict (Step 4)

_TODO: feasible / infeasible / feasible-with-caveats — one paragraph._

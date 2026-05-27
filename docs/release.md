# Release pipeline (QD-14721, Phase C)

## Overview

The release pipeline is split into two stages:

1. **Draft** — `.github/workflows/draft-release.yaml`. Builds native binaries for 5 OS/arch cells × 3 modules (cli, clang, cdnet), generates CycloneDX SBOMs, computes `checksums.txt`, and creates a GitHub release in **draft** state at a pinned commit SHA. **No git tag exists yet** — `gh release create --draft --target $SHA` stores the target SHA in release metadata only.
2. **Publish** — `.github/workflows/publish-release.yaml`. Verifies the draft (`isDraft=true`, `targetCommitish==expected-sha`, full asset name list matches), then runs `gh release edit --draft=false`, which causes GitHub to **materialize the git tag** at the pinned SHA.

This inverts the classic "push tag → CI fires" model. The benefits:

- Deleting a draft cancels the release without ever creating a public tag.
- The human (or umbrella workflow) decides when the tag goes public.
- The exact commit SHA being shipped is recorded in release metadata from the moment of draft creation; downstream consumers can't race with branch drift.

Nightly releases (`.github/workflows/nightly.yaml`) chain `Draft` → `Publish` automatically with no human checkpoint — clang and cdnet are skipped (cli-only), matching the Go pipeline's `skip: IsNightly`.

## Tagged release procedure

1. **Bump source-of-truth.** Edit `gradle.properties` `version=…` to the version you want to release. The new value must be either:
   - equal to the most recent stable `v*` tag (the just-released state — `nightlyVersion` will return `next-patch`), or
   - exactly one semantic bump ahead (patch +1, minor +1, or major +1, with all later segments collapsed to 0; omitted patch normalizes to `.0`).
2. **Commit and push.** The pre-push `checkVersion` hook validates the bump rule. If the version skips a segment (e.g., `2026.3.0` → `2026.3.2`), the push is rejected.
3. **Dispatch `Draft Release`.** From GitHub Actions UI or `gh workflow run draft-release.yaml -f version=<your-version>`. Defaults: `prerelease=false`, `cli-only=false`, `dry-run=false`, `latest=true`.
4. **Inspect the draft.** Open the draft release page on GitHub. Verify the 19 assets are present (15 binaries/archives + 3 SBOMs + 1 checksums.txt) and the `Target` shows the commit SHA you expect.
5. **Dispatch `Publish Release`.** Pass `version`, the full 40-char `expected-sha` from the draft page, and the same `prerelease`/`latest` flags used at draft time.

## Aborting a release

Before publish:

```bash
gh release delete v<version> --yes --cleanup-tag
```

`--cleanup-tag` is a no-op pre-publish — the tag was never materialized. After publish, the tag exists at the captured SHA; `--cleanup-tag` removes it.

## Nightly schedule

The `nightly.yaml` umbrella workflow fires daily at **03:07 UTC** (`cron: '7 3 * * *'`) and is also manually triggerable via `gh workflow run nightly.yaml`.

The `compute` job runs `./gradlew --quiet nightlyVersion`, which emits a marker line `NIGHTLY_VERSION=v<base>`. The workflow strips the `v` prefix, appends `-nightly`, and passes the result downstream — e.g., source-of-truth is `2026.3.0` → nightly version is `2026.3.1-nightly` (one patch bump beyond the last release).

**Important:** if `gradle.properties` `version=dev`, the nightly workflow fails on `compute` with "cannot generate nightly version while gradle.properties has version=dev". This is by design — there must be a planned next release for nightlies to anchor against.

Nightly assets:

- 5 cli archives (`qodana_<os>_<arch>.{tar.gz|zip}`)
- 1 cli SBOM (`qodana-cli-sbom.json`)
- 1 `checksums.txt`

= **7 assets total**.

## Dry-run

`Draft Release` accepts a `dry-run=true` input. With it set, all build, SBOM, and assembly steps run normally, but the final `gh release create` is skipped. The job logs the asset list, checksums.txt content, and release notes. Use this to verify the pipeline against a real version without creating a GitHub release.

## Known limitations

- **Unsigned binaries.** darwin and windows binaries are not codesigned. Downloads will trigger Gatekeeper / SmartScreen warnings. Phase C.1 (QD-12637) will add GPG-signed `checksums.txt.asc` via JetBrains' codesign-client.
- **No nfpm / Homebrew / Scoop.** Phase C ships only the binaries, archives, SBOMs, checksums, and release notes. The Go pipeline also produces apk/deb/rpm/termux/archlinux packages and updates JetBrains/homebrew-utils + JetBrains/scoop-utils tap repos — these surfaces are out of scope here, to be tracked separately.
- **No automated nightly cleanup.** Old nightlies accumulate indefinitely. Manual cleanup:
  ```bash
  gh release list --json tagName,isPrerelease \
    | jq -r '.[] | select(.isPrerelease) | .tagName' \
    | xargs -I{} gh release delete {} --yes --cleanup-tag
  ```
- **No windows/arm64.** GraalVM does not support the `windows-aarch64` target. The matrix is 5 OS/arch, not 6. Tracked separately for when GraalVM lands support.
- **No bit-reproducibility.** GraalVM native image output is not bit-identical across runs even at the same source SHA. `checksums.txt` will differ from a re-run. This is industry-standard for native AOT compilation; consumers verifying integrity against a specific build should download the release-as-published once.

## Tag-creation guarantee

The release model assumes `gh release create --draft <new-tag> --target <sha>` does NOT materialize the git tag at draft time, and that `gh release edit --draft=false` creates it at the pinned SHA. This behavior is the cornerstone of the model — if it ever changes, the inversion breaks and the workflows need a fallback (explicit `git tag` + `git push --tags` in the publish step). The empirical probe described in QD-14721's plan (Task 1) was deferred at the user's discretion; the first real dispatch will exercise the assumption.

## Known follow-ups (re-confirm at QD-14721 PR review)

- **Q1.** GPG signing of `checksums.txt` (Phase C.1 / QD-12637) — file the ticket as part of this PR's chain or after merge.
- **Q2.** nfpm / brew / scoop surfaces — file a follow-up ticket.
- **Q3.** `--version` flag for clang/cdnet (currently only cli has one) — add now or defer.
- **Q4.** Automated nightly cleanup — defer or add a scheduled cleanup workflow.

# `jetbrains-internal` GitHub Environment

A reviewer-gated home for JetBrains-internal secrets that must reach an **approved outside-fork PR**. Any
workflow job needing an internal credential on a fork PR can reference this Environment; its first
consumer is the linter-image `e2e-internal-feed` job (token-using cells `qodana-clang`, `qodana-cdnet`;
later the internal-feed `qodana-jvm`).

It is **not** needed for same-repo branch PRs or pushes — those already read the repo secrets. Without it,
fork-PR token cells **fail red** (fail-loud, per QD-15165): an `environment:` naming a never-created
environment auto-creates an empty one (no reviewers, no secrets), so the job runs immediately with an
empty token and trips the hard-fail gate. Creating the Environment (with reviewers) turns that red into a
**maintainer-approval pause** instead — approve to build, leave it to keep the cells unbuilt.

## Why an Environment

A `pull_request` from a fork gets **no repo secrets**. An Environment with **required reviewers** is the
only way to hand a fork-PR job a secret after a human gate: the job pauses, a maintainer approves, and
_the Environment's own_ secrets unlock for that run (repo/org secrets stay withheld regardless). So every
secret a gated job touches on a fork PR must live on the Environment, not just one of them.

Jobs reference `jetbrains-internal` **only for fork PRs** (a conditional `environment:` expression).
Same-repo PRs, pushes, and `workflow_dispatch` evaluate it to `''` (no Environment, no approval) and use
the repo secrets — unchanged behavior.

## Create it (GitHub UI)

1. **Settings → Environments → New environment.** Name it exactly `jetbrains-internal` (must match the
   workflow string). Save.
2. **Deployment protection rules → Required reviewers → enable.** Add yourself and/or the maintainer
   team (≤ 6 entries; each needs at least read access). One approval releases the run.
   - Leave **"Prevent self-review"** at its default (off). It only blocks a reviewer from approving a run
     **they themselves triggered**; on a fork PR the triggering actor is the external contributor, not
     the approving maintainer, so it never blocks legitimate approval. (Turning it on is harmless here but
     buys nothing.)
   - Leave deployment **branch policies** empty — this design gates on reviewers, not branches.
3. **Environment secrets → Add secret**, one per row below. Use the **same values** as the existing repo
   secrets of the same name (copy them across; the Environment cannot inherit repo secrets). Add further
   secrets here as future jobs need internal credentials on fork PRs.

   | Secret                              | Needed for                                          | Add now?          |
   | ----------------------------------- | --------------------------------------------------- | ----------------- |
   | `QODANA_READ_SPACE_PACKAGES_TOKEN`  | clang/cdnet private clang-tidy mirror (build + e2e) | **Yes**           |
   | `DOCKER_READ_PUBLIC_REGISTRY_USER`  | mandatory `dhi.io` / docker.io base-image login     | **Yes**           |
   | `DOCKER_READ_PUBLIC_REGISTRY_TOKEN` | same                                                | **Yes**           |
   | `QODANA_LICENSE_ONLY_TOKEN`         | the future paid internal-feed `qodana-jvm` cell     | When jvm repoints |

   Omit any of the first three and an approved fork PR fails at build/login instead of running.

## What an approved fork PR looks like

The fork PR's checks show `Docker e2e internal-feed (qodana-clang)` / `(qodana-cdnet)` as **pending —
"Review pending deployments."** Open the run, review the diff, and **Approve** to release the secrets and
run the build; **Reject** to leave the cells unbuilt. The public `Docker e2e (...)` cells run regardless
and give the PR its baseline signal. Without approval the token cells stay **pending** — the controlled
alternative to the fail-red they'd hit with no Environment at all.

Keep the `Docker e2e internal-feed (...)` checks **out of the required-status set** (they aren't required
today), so an unapproved fork PR is never merge-blocked by a deliberately-unbuilt cell.

## Verify the fork-PR path (after setup)

The same-repo CI in the PR that added this only exercises the no-Environment path. To confirm approval
end-to-end:

1. From a throwaway fork, push a no-op change under `linter-images/**` and open a PR here.
2. Confirm `Docker e2e internal-feed (...)` is **pending / awaiting approval** (not skipped, not red).
3. Approve the pending deployment → the cells build + scan with the Environment secrets.
4. (Optional) Reject on a second run → cells stay unbuilt, public cells still green.

# linter-images

Build system for Qodana linter images (`qodana-jvm`, `qodana-jvm-community`, `qodana-android`,
`qodana-android-community`, `qodana-clang`, `qodana-python-community`, `qodana-python`, `qodana-js`).
One source of truth per image: a
thin `docker/images/<slug>.dockerfile` + `<slug>.env`, composed from shared `docker/lib/` includes via
[dockerfile-x](https://github.com/devthefuture-org/dockerfile-x).

## Reconstruct an image locally (public path)

The hardened base lives on `dhi.io`, which requires a free login (no paid account needed). The build
`image-tool` always comes from the repo (the `tooling` build context), so stage it with `installDist`
first:

    docker login dhi.io
    ./gradlew :linter-images:installDist
    cd linter-images
    docker compose build qodana-jvm

`compose.yaml` uses `CLI_SOURCE=release`: the inner CLI is downloaded inside the builder stage, and the
build `image-tool` is supplied by the `tooling` context (`build/install/image-tool` from `installDist`).
No other from-tree build is required, and `QD_FEED_TOKEN` can be unset — the IDE dist is fetched from the
public mirror and GPG- + sha256-verified, fail-closed.

An image selects its feed by setting `QD_DISTRIBUTION_FEED` in its own `.env`; absent (as in the current
`jvm`/`android` images) it defaults to the public feed above. A private feed additionally needs
`QD_FEED_TOKEN`, supplied by the private overlay (the only file that defines the `feed_token` secret);
the token is sent whenever `QD_FEED_TOKEN` is present:

    QD_FEED_TOKEN=<token> docker compose -f compose.yaml -f compose.private.yaml build qodana-jvm

`qodana-clang` always needs the private overlay: its clang-tidy archive comes from the private
qodana-cli-deps mirror, so the build mounts a `QODANA_CLI_DEPS_TOKEN` (a JB Space bearer token) secret —
it cannot build from the bare `compose.yaml`. clang has no IDE dist, so it does NOT need `QD_FEED_TOKEN`:

    QODANA_CLI_DEPS_TOKEN=<bearer-token> docker compose -f compose.yaml -f compose.private.yaml build qodana-clang

## Build from this tree (CI path)

CI layers `compose.ci.yaml` to test exactly the code under review (`CLI_SOURCE=context`, inner CLI from
`assembleRelease`; the `image-tool` `tooling` context is already in the base compose):

    ./gradlew :qodana-cli:assembleRelease -PtargetOs=linux -PtargetArch=amd64
    ./gradlew :linter-images:installDist
    docker compose -f compose.yaml -f compose.ci.yaml build qodana-jvm

# linter-images

Build system for Qodana linter images (`qodana-jvm`, `qodana-jvm-community`, `qodana-android`,
`qodana-android-community`, `qodana-clang`, `qodana-python-community`, `qodana-python`, `qodana-js`,
`qodana-go`, `qodana-php`, `qodana-rust`, `qodana-ruby`, `qodana-dotnet`, `qodana-cpp`, `qodana-cdnet`). One source of truth per image: a thin `docker/images/<slug>.dockerfile` + `<slug>.env`,
composed from shared `docker/lib/` includes via [dockerfile-x](https://github.com/devthefuture-org/dockerfile-x).

## Build profiles

Each dist image has two build profiles. Feed, verification, and the dist pin travel together; pick one
with a compose overlay.

### Release (public, token-free) — `compose.release.yaml`

Reproduces the public-release dist: the public feed, GPG- + sha256-verified, no token. This is the
customer-reproduction path. The hardened base lives on `dhi.io`; the build `image-tool` always comes from
the repo (`tooling` context), so stage it first:

    docker login dhi.io
    ./gradlew :linter-images:installDist
    cd linter-images
    docker compose -f compose.yaml -f compose.release.yaml build qodana-jvm

`compose.yaml` uses `CLI_SOURCE=release` (the inner CLI is downloaded in the builder stage). The overlay
overrides each image's `.env` back to the public feed + its pinned public release build.

### Nightly (internal feed) — needs a token

Images repointed onto the internal nightly dist feed (`packages.jetbrains.team/.../qodana-dist-internal`,
sha256-verified) need the read-only `QODANA_READ_SPACE_PACKAGES_TOKEN`, exposed as the
`space_packages_token` secret by the private overlay:

    QODANA_READ_SPACE_PACKAGES_TOKEN=<token> docker compose -f compose.yaml -f compose.private.yaml build qodana-jvm

For an internal-feed image (today: `qodana-jvm`), a bare `docker compose build <slug>` (no overlay, no
token) hits the nightly feed and fails with a `sha256 requires token` error — use one of the profiles
above. Public-feed images (the rest) still build bare from the public feed.

### `qodana-clang` / `qodana-cdnet` (no IDE dist)

These embed no IDE dist; their aux tool (clang-tidy / ReSharper CLT) comes from the private
qodana-cli-deps mirror, read with the same token, so they always need the private overlay:

    QODANA_READ_SPACE_PACKAGES_TOKEN=<bearer-token> docker compose -f compose.yaml -f compose.private.yaml build qodana-clang
    QODANA_READ_SPACE_PACKAGES_TOKEN=<bearer-token> docker compose -f compose.yaml -f compose.private.yaml build qodana-cdnet

## Build from this tree (CI path)

CI layers `compose.ci.yaml` to test the code under review (`CLI_SOURCE=context`, inner CLI from
`assembleRelease`; the `image-tool` `tooling` context is already in the base compose). Add
`compose.private.yaml` for an internal-feed image:

    ./gradlew :qodana-cli:assembleRelease -PtargetOs=linux -PtargetArch=amd64
    ./gradlew :linter-images:installDist
    docker compose -f compose.yaml -f compose.ci.yaml -f compose.private.yaml build qodana-jvm

# toolchain/go — redirect the Go module cache to the writable /data mount.
# In-place (no `FROM`): layers onto the current stage — `base` for go (Go itself is PRE-BAKED in the
# dhi.io/golang base at /usr/local/go, already on PATH for every stage incl. the runtime user, so this
# fragment installs NOTHING). The golang base defaults GOMODCACHE to /go/pkg/mod, which is ROOT-owned;
# the image runs scans as the unprivileged qodana user (uid 1000), so any Go project that resolves
# external modules would fail to populate that cache. lib/base.dockerfile creates /data/cache owned by
# the qodana user, so point GOMODCACHE there — mirroring the source qodana-cli go.Dockerfile's
# `ENV GOMODCACHE="$QODANA_DATA/cache/go"`. Consumes: nothing.
ENV GOMODCACHE=/data/cache/go

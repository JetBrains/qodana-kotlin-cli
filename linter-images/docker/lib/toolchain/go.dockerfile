# toolchain/go — redirect the Go module cache to the writable /data mount.
# In-place (no `FROM`): layers onto the current stage — `base` for go (Go itself is PRE-BAKED in the
# dhi.io/golang base at /usr/local/go, already on PATH for every stage incl. the runtime user, so this
# fragment installs NOTHING). The golang base defaults GOMODCACHE to /go/pkg/mod, which is ROOT-owned;
# the image runs scans as the unprivileged qodana user (uid 1000), so any Go project that resolves
# external modules would fail to populate that cache. lib/base.dockerfile creates /data/cache owned by
# the qodana user, so point GOMODCACHE there — mirroring the source qodana-cli go.Dockerfile's
# `ENV GOMODCACHE="$QODANA_DATA/cache/go"`. Consumes: nothing.
ENV GOMODCACHE=/data/cache/go

# The dhi.io/golang base does not carry a writable /home/qodana through to the runtime image (unlike the
# trixie/ruby/php bases), and the IDE resolves user.home from /etc/passwd — NOT the $HOME env — so GoLand
# dies at startup creating its home-rooted dirs (java.nio.file.AccessDeniedException: /home/qodana, for
# ~/.local/share, ~/.java, the lock/system dir). Repoint the qodana user's home at a dedicated dir under
# the /data tree: base.dockerfile chowns /data to uid 1000, and this dir is not a bind-mount target, so it
# persists with the image and is writable whether or not cache/results volumes are mounted. Kept separate
# from /data/cache so the IDE's home-rooted state never collides with the scan's --cache-dir. Bare ARGs
# inherit the global QODANA_UID/GID (base.dockerfile); a `=1000` default here would shadow them.
ARG QODANA_UID
ARG QODANA_GID
RUN <<-EOT
	set -eux
	mkdir -p /data/qodana-home
	chown "${QODANA_UID}:${QODANA_GID}" /data/qodana-home
	usermod --home /data/qodana-home qodana
EOT

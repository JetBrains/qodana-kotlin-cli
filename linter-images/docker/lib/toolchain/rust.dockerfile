# toolchain/rust — rustup-init (sha-pinned) + the RUST_VERSION toolchain + rust-src. rust-only.
# An install STAGE (the conda/clang pattern: `FROM base AS rust-toolchain`) that the dist layers onto
# via DIST_BASE_STAGE=rust-toolchain. The trixie base ships NO Rust, so this fetches a sha-verified
# rustup-init and installs the toolchain. Consumes: RUST_VERSION RUSTUP_INIT_SHA256.  Layers onto `base`.
ARG RUST_VERSION
ARG RUSTUP_INIT_SHA256

FROM base AS rust-toolchain
ARG RUST_VERSION
ARG RUSTUP_INIT_SHA256

# CARGO_HOME/RUSTUP_HOME sit on the STABLE /usr/local — NOT under /data/cache. The runtime bind-mounts
# an empty host dir over /data/cache for the writable scan cache, which would SHADOW anything baked
# there; the cargo/rustc/rustup PROXIES rustup-init writes to $CARGO_HOME/bin must stay reachable on
# PATH at scan time, so they live at /usr/local/cargo/bin (conda keeps its bin at /opt/miniconda3, go
# keeps go at /usr/local/go, for the same reason). cargo's registry/git cache lives under $CARGO_HOME
# too and is the writable scan-time target (the go GOMODCACHE analog), so /usr/local/cargo is chowned
# to uid 1000 below — the registry is rebuilt per container (ephemeral, not on the mount), which is
# fine for a scan. Set BEFORE rustup-init runs so the proxies install under CARGO_HOME, not ~/.cargo.
ENV RUSTUP_HOME=/usr/local/rustup \
	CARGO_HOME=/usr/local/cargo \
	PATH=/usr/local/cargo/bin:/usr/local/rustup/bin:${PATH}

# build-essential + pkg-config are RUNTIME deps: native crates compile C and probe system libs at scan
# time, so they are NOT purged (unlike clang/node's transient gnupg). rustup-init is sha-verified
# fail-closed (the conda/android installer-pin convention) before exec; amd64-only fleet, so the
# x86_64-unknown-linux-gnu installer is fetched directly (no TARGETPLATFORM switch). rustup-init is a
# self-contained static binary — its sha is RUST_VERSION-independent (it installs whatever toolchain
# --default-toolchain names), so RUSTUP_INIT_SHA256 is a manually-maintained pin, refreshed only when
# rustup ships a new installer (re-verify against the upstream .sha256 sidecar).
ADD --checksum=sha256:${RUSTUP_INIT_SHA256} \
	https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init \
	/tmp/rustup-init
# hadolint ignore=DL4006
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends \
		build-essential \
		pkg-config
	# Defense-in-depth: ADD --checksum already gates the fetch, but re-verify so a future edit that
	# swaps ADD for curl cannot silently drop the check (the conda/android pattern).
	echo "${RUSTUP_INIT_SHA256}  /tmp/rustup-init" | sha256sum -c -
	chmod +x /tmp/rustup-init
	/tmp/rustup-init -y --no-modify-path --default-toolchain "${RUST_VERSION}"
	rustup component add rust-src
	# rustup-init + the toolchain run as root here ($HOME=/root); the cargo/rustc proxies + the registry
	# cache live under CARGO_HOME=/usr/local/cargo. chown it to uid 1000 so the scan-time `cargo fetch`
	# can write its registry as the unprivileged runtime user, and make RUSTUP_HOME world-readable so
	# uid 1000 can read the toolchain (it never writes there).
	chown -R 1000:1000 /usr/local/cargo
	chmod -R a+rX /usr/local/rustup
	rm -f /tmp/rustup-init
	rm -rf /var/lib/apt/lists/*
EOT

# toolchain/conda — Miniconda3 (sha-pinned installer) + poetry + pipenv via conda-forge. python-only.
# First NEW toolchain; the reusable template for the upcoming rust/dotnet stages: a clean orthogonal
# `FROM base AS <name>-toolchain` that the dist layers onto via DIST_BASE_STAGE (mirrors android).
# Consumes: MINICONDA_VERSION MINICONDA_SHA256.  Layers onto `base`.
#
# Paths are LITERAL (/opt/miniconda3, /data/cache, /home/qodana): base sets neither $HOME nor
# $QODANA_DATA, and a build-time RUN runs as root ($HOME=/root) — so the source image's $HOME/
# $QODANA_DATA interpolation would write to the wrong tree. The runtime drops to UID 1000 (home
# /home/qodana), so every scan-time write target is chowned 1000 or ENV-redirected under /data/cache.
ARG MINICONDA_VERSION
ARG MINICONDA_SHA256

FROM base AS conda-toolchain
ARG MINICONDA_VERSION
ARG MINICONDA_SHA256

# Miniconda installer: sha-verified declarative fetch (DL3020 waived in .hadolint.yaml). x86_64 only —
# the fleet is amd64-only (CLI_ARCH=amd64), matching tini/android-sdk.
ADD --checksum=sha256:${MINICONDA_SHA256} \
	https://repo.anaconda.com/miniconda/Miniconda3-${MINICONDA_VERSION}-Linux-x86_64.sh \
	/tmp/miniconda.sh

# bzip2 (the installer needs it) + the native shared libs conda-shipped wheels link against at scan
# time (libglib2.0-0/libsm6/libxext6/libxrender1) — runtime deps, so NOT purged (unlike node/clang's
# transient gnupg). conda/poetry/pipenv install from conda-forge at BUILD time; the runtime needs no net.
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends \
		bzip2 \
		libglib2.0-0 \
		libsm6 \
		libxext6 \
		libxrender1
	bash /tmp/miniconda.sh -b -p /opt/miniconda3
	ln -sf /opt/miniconda3/etc/profile.d/conda.sh /etc/profile.d/conda.sh
	find /opt/miniconda3/ -follow -type f -name '*.a' -delete
	find /opt/miniconda3/ -follow -type f -name '*.js.map' -delete
	/opt/miniconda3/bin/conda install -y -c conda-forge poetry pipenv
	/opt/miniconda3/bin/conda clean -afy
	# poetry config under the runtime user's HOME (RUN is root → $HOME=/root), so the config lands in
	# /home/qodana/.config/pypoetry where UID 1000 reads it at scan time.
	HOME=/home/qodana /opt/miniconda3/bin/poetry config virtualenvs.create false
	# Scan-time write targets, made writable for UID 1000: conda pkgs/lockfiles (/opt/miniconda3),
	# conda envs + pip/poetry caches (/data/cache/*), and the home-rooted state conda/poetry touch
	# (~/.conda/environments.txt, ~/.condarc, ~/.config/pypoetry, ~/.local).
	mkdir -p \
		/data/cache/conda/envs \
		/data/cache/.pip \
		/data/cache/.poetry \
		/home/qodana/.conda \
		/home/qodana/.config \
		/home/qodana/.local
	chown -R 1000:1000 /opt/miniconda3 /data/cache /home/qodana
	rm -f /tmp/miniconda.sh
	rm -rf /var/lib/apt/lists/*
EOT

# Runtime env the scan needs: conda on PATH, caches redirected under the writable /data/cache, poetry
# in non-virtualenv mode. Set here (after install) so it propagates down the FROM lineage
# (conda-toolchain → dist → cli-installed → runtime), exactly like android's ANDROID_HOME.
ENV CONDA_DIR=/opt/miniconda3 \
	CONDA_ENVS_PATH=/data/cache/conda/envs \
	PIP_CACHE_DIR=/data/cache/.pip \
	POETRY_CACHE_DIR=/data/cache/.poetry \
	FLIT_ROOT_INSTALL=1 \
	PATH=/opt/miniconda3/bin:/home/qodana/.local/bin:${PATH}

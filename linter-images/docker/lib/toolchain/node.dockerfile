# toolchain/node — Node (NodeSource, NODE_MAJOR-pinned) + Yarn via corepack. jvm-only.
# Consumes: NODE_MAJOR.  Layers onto the `base` stage.
ARG NODE_MAJOR

# DL4006 is satisfied: set -eux + the heredoc body run under /bin/sh -e.
# hadolint ignore=DL4006
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	mkdir -p /etc/apt/keyrings
	curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key \
		| gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg
	echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_${NODE_MAJOR}.x nodistro main" \
		> /etc/apt/sources.list.d/nodesource.list
	apt-get update
	apt-get install -y --no-install-recommends nodejs
	corepack enable
	corepack prepare yarn@stable --activate
	rm -rf /var/lib/apt/lists/*
EOT

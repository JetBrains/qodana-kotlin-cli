# toolchain/eslint — global ESLint at the pinned version for JS/TS analysis.
# In-place (no `FROM`): layers onto the current stage — `base` for js (node from the DHI base), or a
# future node-toolchain stage for go/php/ruby. The version is read from a renovate-tracked package.json
# (lib/toolchain/eslint/package.json) so a bump is a reviewable PR, mirroring conda-requirements.txt.
# npm's global prefix on the node base is /usr (binary lands at /usr/bin/eslint, root-owned + world-
# readable), so the runtime user needs no chown to execute it. npm's cache is cleaned (like conda's
# `conda clean -afy`) so no build-time root state ships in the layer.
COPY lib/toolchain/eslint/package.json /tmp/eslint/package.json

# DL4006 is satisfied: set -eux + the heredoc body run under /bin/sh -e.
# hadolint ignore=DL4006
RUN <<-EOT
	set -eux
	eslint_version="$(node -p "require('/tmp/eslint/package.json').dependencies.eslint")"
	npm install -g --no-fund --no-audit "eslint@${eslint_version}"
	# Contract check: the INSTALLED version must equal the pin, not merely "some eslint".
	test "$(eslint --version)" = "v${eslint_version}"
	npm cache clean --force
	rm -rf /tmp/eslint
EOT

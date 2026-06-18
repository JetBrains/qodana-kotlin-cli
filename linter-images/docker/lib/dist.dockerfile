# dist — provision + verify the IDE distribution into ${QD_DIST}.
# A JDK builder runs image-tool (provision-dist: GPG signer-match THEN sha256, fail-closed;
# then verify-dist-layout: product-info code + bundled JBR runtime — NOT a complete JDK, the JBR
# ships no jar/jdk.jartool by design). The final stage COPYs only the verified tree. android reuses
# jvm's dist (QD_LINTER_SLUG=qodana-jvm for both).
# Consumes: QD_LINTER_SLUG QD_VERSION QD_BUILD QD_DISTRIBUTION_FEED
#           QD_PRODUCT_INFO_CODE QD_DIST JDK_BUILDER_IMAGE
ARG QD_LINTER_SLUG
ARG QD_VERSION
ARG QD_BUILD
ARG QD_DISTRIBUTION_FEED=https://download.jetbrains.com/qodana/feed
ARG QD_PRODUCT_INFO_CODE
ARG QD_DIST=/opt/idea
# JDK_BUILDER_IMAGE default + global scope live in lib/base.dockerfile (FROM-ARGs must be declared
# before the first FROM); this FROM consumes that global value.
FROM ${JDK_BUILDER_IMAGE} AS dist-builder
# QD_LINTER_SLUG/QD_VERSION/QD_BUILD/QD_PRODUCT_INFO_CODE are global .env ARGs, so the bare
# re-declarations inherit them. QD_DISTRIBUTION_FEED/QD_DIST are NOT .env keys — their defaults live on
# the pre-FROM lines above, which are inter-stage (not global), so they must be re-defaulted HERE or the
# `set -u` RUN below fails with "parameter not set".
ARG QD_LINTER_SLUG
ARG QD_VERSION
ARG QD_BUILD
ARG QD_DISTRIBUTION_FEED=https://download.jetbrains.com/qodana/feed
ARG QD_PRODUCT_INFO_CODE
ARG QD_DIST=/opt/idea
# gnupg + curl for image-tool's signature verification and downloads.
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends curl gnupg
	rm -rf /var/lib/apt/lists/*
EOT
# The vendored JetBrains public key + its fingerprint travel with the context (lib/). Copy to an
# absolute dir (no WORKDIR is set in this builder, so a relative dest would be DL3045-ambiguous).
COPY lib/jetbrains.pub /build/lib/jetbrains.pub
COPY lib/jetbrains.pub.fpr /build/lib/jetbrains.pub.fpr
# image-tool is bind-mounted from the `tooling` named context (NOT copied into a layer).
# feed_token is optional; image-tool sends it unconditionally whenever it is present (the public feed
# ignores it), so we export it whenever the secret is mounted.
RUN --mount=type=bind,from=tooling,target=/tooling \
	--mount=type=secret,id=feed_token,required=false <<-EOT
	set -eux
	if [ -f /run/secrets/feed_token ]; then
		set +x  # never echo the secret under xtrace; token flows via env, not argv
		QD_FEED_TOKEN="$(cat /run/secrets/feed_token)"
		export QD_FEED_TOKEN
		set -x
	fi
	/tooling/bin/image-tool provision-dist \
		--distribution-feed "${QD_DISTRIBUTION_FEED}" \
		--linter-slug "${QD_LINTER_SLUG}" \
		--version "${QD_VERSION}" \
		--build "${QD_BUILD}" \
		--gpg-key /build/lib/jetbrains.pub \
		--gpg-fingerprint "$(cat /build/lib/jetbrains.pub.fpr)" \
		--target "${QD_DIST}"
	/tooling/bin/image-tool verify-dist-layout \
		--dist "${QD_DIST}" \
		--expected-product-code "${QD_PRODUCT_INFO_CODE}"
	# The dist ships a build-machine idea.log.path baked into bin/<ide>64.vmoptions (the TeamCity
	# agent path /mnt/agent/work/<id>/logs), which overrides the writable log.path the CLI sets at
	# launch and then fails as the unprivileged runtime user. The vmoptions file is named per IDE
	# (idea64 for IU/IC, webstorm64 for WS, pycharm64 for PY/PC), so strip the line from every
	# bin/*.vmoptions so the CLI's value applies regardless of product.
	for f in "${QD_DIST}"/bin/*.vmoptions; do
		[ -f "$f" ] || continue
		sed -i '/^-Didea\.log\.path=/d' "$f"
	done
EOT

# Final stage: take only the verified distribution onto the dist base. Defaults to `base`; android
# sets DIST_BASE_STAGE=android-toolchain (via INCLUDE_ARGS → global ARG) so the dist layers onto the
# SDK/Corretto stage instead of bypassing it. The `${VAR:-default}` expansion keeps this a global-scope
# FROM-ARG: a plain ${DIST_BASE_STAGE} + a mid-file ARG would be inter-stage, blanking the base name.
FROM ${DIST_BASE_STAGE:-base} AS dist
ARG QD_DIST=/opt/idea
# Bare (no default): inherit the global QODANA_UID/GID (cli.dockerfile shadowing trap).
ARG QODANA_UID
ARG QODANA_GID
COPY --from=dist-builder --chown=${QODANA_UID}:${QODANA_GID} ${QD_DIST} ${QD_DIST}

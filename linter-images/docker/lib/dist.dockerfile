# dist — provision + verify the IDE distribution into ${QD_DIST}.
# A JDK builder runs image-tool (provision-dist: GPG signer-match THEN sha256, fail-closed;
# then verify-dist-layout: product-info code + bundled JBR runtime — NOT a complete JDK, the JBR
# ships no jar/jdk.jartool by design). The final stage COPYs only the verified tree. android reuses
# jvm's dist (QD_LINTER_SLUG=qodana-jvm for both).
# Consumes: QD_LINTER_SLUG QD_VERSION QD_BUILD QD_CHANNEL QD_FEED_URL
#           QD_PRODUCT_INFO_CODE QD_DIST JDK_BUILDER_IMAGE
ARG QD_LINTER_SLUG
ARG QD_VERSION
ARG QD_BUILD
ARG QD_CHANNEL=public
ARG QD_FEED_URL=https://download.jetbrains.com/qodana/feed
ARG QD_PRODUCT_INFO_CODE
ARG QD_DIST=/opt/idea
# JDK_BUILDER_IMAGE default + global scope live in lib/base.dockerfile (FROM-ARGs must be declared
# before the first FROM); this FROM consumes that global value.
FROM ${JDK_BUILDER_IMAGE} AS dist-builder
# QD_LINTER_SLUG/QD_VERSION/QD_BUILD/QD_CHANNEL/QD_PRODUCT_INFO_CODE are global .env ARGs, so the bare
# re-declarations inherit them. QD_FEED_URL/QD_DIST are NOT .env keys — their defaults live on the
# pre-FROM lines above, which are inter-stage (not global), so they must be re-defaulted HERE or the
# `set -u` RUN below fails with "parameter not set".
ARG QD_LINTER_SLUG
ARG QD_VERSION
ARG QD_BUILD
ARG QD_CHANNEL
ARG QD_FEED_URL=https://download.jetbrains.com/qodana/feed
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
# feed_token is optional; ONLY the private channel reads it (matches provision-dist's decision).
RUN --mount=type=bind,from=tooling,target=/tooling \
	--mount=type=secret,id=feed_token,required=false <<-EOT
	set -eux
	if [ "${QD_CHANNEL}" = "private" ] && [ -f /run/secrets/feed_token ]; then
		QD_FEED_TOKEN="$(cat /run/secrets/feed_token)"
		export QD_FEED_TOKEN
	fi
	/tooling/bin/image-tool provision-dist \
		--feed-url "${QD_FEED_URL}" \
		--linter-slug "${QD_LINTER_SLUG}" \
		--version "${QD_VERSION}" \
		--build "${QD_BUILD}" \
		--channel "${QD_CHANNEL}" \
		--gpg-key /build/lib/jetbrains.pub \
		--gpg-fingerprint "$(cat /build/lib/jetbrains.pub.fpr)" \
		--target "${QD_DIST}"
	/tooling/bin/image-tool verify-dist-layout \
		--dist "${QD_DIST}" \
		--expected-product-code "${QD_PRODUCT_INFO_CODE}"
EOT

# Final stage: take only the verified distribution onto the base layer.
FROM base AS dist
ARG QD_DIST=/opt/idea
COPY --from=dist-builder --chown=1000:1000 ${QD_DIST} ${QD_DIST}

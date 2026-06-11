# dist — provision + verify the IDE distribution into ${QD_DIST}.
# A JDK builder runs image-tool (provision-dist: GPG signer-match THEN sha256, fail-closed;
# then verify-dist-layout: complete-JBR + product-info code). The final stage COPYs only
# the verified tree. android reuses jvm's dist (QD_LINTER_SLUG=qodana-jvm for both).
# Consumes: QD_LINTER_SLUG QD_VERSION QD_BUILD QD_CHANNEL QD_FEED_URL
#           QD_PRODUCT_INFO_CODE QD_DIST JDK_BUILDER_IMAGE
ARG QD_LINTER_SLUG
ARG QD_VERSION
ARG QD_BUILD
ARG QD_CHANNEL=public
ARG QD_FEED_URL=https://download.jetbrains.com/qodana/feed
ARG QD_PRODUCT_INFO_CODE
ARG QD_DIST=/opt/idea
# JDK builder default: eclipse-temurin 25 (Ubuntu-based: ships tar + coreutils sha256sum + apt),
# pinned by manifest-list digest. NOT a .env key — overridable per build (Renovate-tracked).
ARG JDK_BUILDER_IMAGE=eclipse-temurin:25-jdk@sha256:edb3aa0f621796d8f5f9d602c7611ffdf015cd89e6ddda1894d85a3a99d170a8

FROM ${JDK_BUILDER_IMAGE} AS dist-builder
ARG QD_LINTER_SLUG
ARG QD_VERSION
ARG QD_BUILD
ARG QD_CHANNEL
ARG QD_FEED_URL
ARG QD_PRODUCT_INFO_CODE
ARG QD_DIST
# gnupg + curl for image-tool's signature verification and downloads.
RUN <<-EOT
	set -eux
	export DEBIAN_FRONTEND=noninteractive
	apt-get update
	apt-get install -y --no-install-recommends curl gnupg
	rm -rf /var/lib/apt/lists/*
EOT
# The vendored JetBrains public key + its fingerprint travel with the context (lib/).
COPY lib/jetbrains.pub lib/jetbrains.pub
COPY lib/jetbrains.pub.fpr lib/jetbrains.pub.fpr
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
		--gpg-key lib/jetbrains.pub \
		--gpg-fingerprint "$(cat lib/jetbrains.pub.fpr)" \
		--target "${QD_DIST}"
	/tooling/bin/image-tool verify-dist-layout \
		--dist "${QD_DIST}" \
		--expected-product-code "${QD_PRODUCT_INFO_CODE}"
EOT

# Final stage: take only the verified distribution onto the base layer.
FROM base AS dist
ARG QD_DIST=/opt/idea
COPY --from=dist-builder --chown=1000:1000 ${QD_DIST} ${QD_DIST}

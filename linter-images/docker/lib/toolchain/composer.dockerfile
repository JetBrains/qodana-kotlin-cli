# toolchain/composer — Composer (the PHP dependency manager) from the pinned upstream image.
# The dhi.io/php base ships PHP pre-baked but NO composer; the source qodana-cli php.Dockerfile
# provisions it via `COPY --from=composer:<ver> /usr/bin/composer`. This fragment mirrors that with a
# multi-stage COPY off a digest-pinned COMPOSER_IMAGE (an .env key, like android's CORRETTO*_IMAGE).
#
# Unlike the in-place node/eslint fragments (no `FROM`, layers onto `base`), a cross-image COPY needs a
# source stage, so this opens its own `php-toolchain` stage (mirroring android-toolchain): node + eslint
# have already layered onto `base` in-place, and `FROM base AS php-toolchain` inherits them, then the
# COPY lands composer on top. qodana-php sets DIST_BASE_STAGE=php-toolchain so the dist FROMs this stage
# (the conda/android pattern), keeping composer in the shipped image. The composer phar is root-owned +
# world-readable, so the runtime user needs no chown to execute it. COMPOSER_IMAGE is an .env key,
# emitted by dockerfile-x's INCLUDE_ARGS at global pre-FROM scope, so both FROMs below resolve it.
# Consumes: COMPOSER_IMAGE.
FROM ${COMPOSER_IMAGE} AS composer-base

FROM base AS php-toolchain
COPY --from=composer-base /usr/bin/composer /usr/bin/composer

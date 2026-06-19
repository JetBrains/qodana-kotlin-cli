package org.jetbrains.qodana.images

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Guards the Composer toolchain (QD-15039). The dhi.io/php base ships PHP pre-baked but NO composer; the
 * source qodana-cli `php.Dockerfile` provisions it via `COPY --from=composer:2.8.10 /usr/bin/composer`.
 * `lib/toolchain/composer.dockerfile` mirrors that: a multi-stage `COPY --from` off a digest-pinned
 * `COMPOSER_IMAGE` (an `.env` pin, like android's CORRETTO*_IMAGE) lands the self-contained composer phar
 * at `/usr/bin/composer`. EnvContractTest asserts the COMPOSER_IMAGE pin; this reads the fragment + the
 * php thin image directly (the COPY target + the INCLUDE wiring are not visible to EnvContractTest).
 */
class ComposerToolchainTest {
    private val lib: Path = Path.of("docker/lib")
    private val images: Path = Path.of("docker/images")

    @Test
    fun `composer toolchain fragment copies the composer binary from the pinned image`() {
        val composer = lib.resolve("toolchain/composer.dockerfile").readText()
        assertTrue(
            Regex("""(?m)^FROM \$\{COMPOSER_IMAGE\} AS composer-base$""").containsMatchIn(composer),
            "composer toolchain must stage the pinned COMPOSER_IMAGE as composer-base",
        )
        assertTrue(
            Regex("""(?m)^COPY --from=composer-base \S*/composer /usr/bin/composer$""").containsMatchIn(composer),
            "composer toolchain must COPY /usr/bin/composer from composer-base (the self-contained phar)",
        )
    }

    @Test
    fun `qodana-php thin image includes the composer toolchain fragment`() {
        val thin = images.resolve("qodana-php.dockerfile").readText()
        assertTrue(
            Regex("""(?m)^INCLUDE lib/toolchain/composer\.dockerfile$""").containsMatchIn(thin),
            "qodana-php.dockerfile must INCLUDE lib/toolchain/composer.dockerfile (the composer COPY)",
        )
    }
}

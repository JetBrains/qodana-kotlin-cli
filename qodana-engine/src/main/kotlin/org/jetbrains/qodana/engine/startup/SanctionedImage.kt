package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.env.QodanaEnv

/**
 * A "sanctioned" Qodana image is one this repo builds: it sets QODANA_DOCKER and bakes a native
 * IDE dist at QODANA_DIST. Inside such an image `--linter <x>` must route to the baked dist
 * (NATIVE), never to a feed install — we never download a dist at scan time.
 */
object SanctionedImage {
    fun isSanctioned(getEnv: (String) -> String? = System::getenv): Boolean =
        !getEnv(QodanaEnv.DOCKER).isNullOrBlank() && !getEnv(QodanaEnv.DIST).isNullOrBlank()
}

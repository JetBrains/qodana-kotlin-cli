package org.jetbrains.qodana.engine.startup

import org.jetbrains.qodana.core.env.QodanaEnv
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SanctionedImageTest {
    private fun env(vararg pairs: Pair<String, String?>): (String) -> String? {
        val m = pairs.toMap()
        return { m[it] }
    }

    @Test
    fun `sanctioned when both QODANA_DOCKER and QODANA_DIST are set`() {
        assertTrue(
            SanctionedImage.isSanctioned(env(QodanaEnv.DOCKER to "true", QodanaEnv.DIST to "/opt/idea")),
        )
    }

    @Test
    fun `not sanctioned when QODANA_DIST is absent`() {
        assertFalse(SanctionedImage.isSanctioned(env(QodanaEnv.DOCKER to "true", QodanaEnv.DIST to null)))
    }

    @Test
    fun `not sanctioned when QODANA_DOCKER is absent`() {
        assertFalse(SanctionedImage.isSanctioned(env(QodanaEnv.DOCKER to null, QodanaEnv.DIST to "/opt/idea")))
    }

    @Test
    fun `not sanctioned when either is blank`() {
        assertFalse(SanctionedImage.isSanctioned(env(QodanaEnv.DOCKER to "", QodanaEnv.DIST to "/opt/idea")))
        assertFalse(SanctionedImage.isSanctioned(env(QodanaEnv.DOCKER to "true", QodanaEnv.DIST to "")))
    }
}

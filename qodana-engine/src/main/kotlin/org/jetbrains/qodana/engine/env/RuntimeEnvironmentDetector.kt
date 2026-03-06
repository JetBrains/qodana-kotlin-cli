package org.jetbrains.qodana.engine.env

import org.jetbrains.qodana.core.env.QodanaEnv

enum class RuntimeEnvironment {
    HOST,
    IN_DOCKER,
}

object RuntimeEnvironmentDetector {
    fun detect(getEnv: (String) -> String? = System::getenv): RuntimeEnvironment {
        return if (getEnv(QodanaEnv.DOCKER).isNullOrBlank()) {
            RuntimeEnvironment.HOST
        } else {
            RuntimeEnvironment.IN_DOCKER
        }
    }
}

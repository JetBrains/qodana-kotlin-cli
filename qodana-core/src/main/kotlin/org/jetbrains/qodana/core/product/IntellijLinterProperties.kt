package org.jetbrains.qodana.core.product

data class IntellijLinterProperties(
    val linter: Linter,
    val productInfoJsonCode: String,
    val feedProductCode: String,
    val vmOptionsEnv: String,
    val scriptName: String,
) {
    val presentableName: String get() = linter.presentableName

    companion object {
        val JVM =
            IntellijLinterProperties(
                linter = Linters.JVM,
                productInfoJsonCode = "IU",
                feedProductCode = "IIU",
                vmOptionsEnv = "IDEA_VM_OPTIONS",
                scriptName = "idea",
            )

        val JVM_COMMUNITY =
            IntellijLinterProperties(
                linter = Linters.JVM_COMMUNITY,
                productInfoJsonCode = "IC",
                feedProductCode = "IIC",
                vmOptionsEnv = "IDEA_VM_OPTIONS",
                scriptName = "idea",
            )

        val ANDROID =
            IntellijLinterProperties(
                linter = Linters.ANDROID,
                productInfoJsonCode = "IU",
                feedProductCode = "",
                vmOptionsEnv = "IDEA_VM_OPTIONS",
                scriptName = "idea",
            )

        val ANDROID_COMMUNITY =
            IntellijLinterProperties(
                linter = Linters.ANDROID_COMMUNITY,
                productInfoJsonCode = "IC",
                feedProductCode = "",
                vmOptionsEnv = "IDEA_VM_OPTIONS",
                scriptName = "idea",
            )

        val PHP =
            IntellijLinterProperties(
                linter = Linters.PHP,
                productInfoJsonCode = "PS",
                feedProductCode = "PS",
                vmOptionsEnv = "PHPSTORM_VM_OPTIONS",
                scriptName = "phpstorm",
            )

        val PYTHON =
            IntellijLinterProperties(
                linter = Linters.PYTHON,
                productInfoJsonCode = "PY",
                feedProductCode = "PCP",
                vmOptionsEnv = "PYCHARM_VM_OPTIONS",
                scriptName = "pycharm",
            )

        val PYTHON_COMMUNITY =
            IntellijLinterProperties(
                linter = Linters.PYTHON_COMMUNITY,
                productInfoJsonCode = "PC",
                feedProductCode = "PCC",
                vmOptionsEnv = "PYCHARM_VM_OPTIONS",
                scriptName = "pycharm",
            )

        val JS =
            IntellijLinterProperties(
                linter = Linters.JS,
                productInfoJsonCode = "WS",
                feedProductCode = "WS",
                vmOptionsEnv = "WEBIDE_VM_OPTIONS",
                scriptName = "webstorm",
            )

        val DOTNET =
            IntellijLinterProperties(
                linter = Linters.DOTNET,
                productInfoJsonCode = "RD",
                feedProductCode = "RD",
                vmOptionsEnv = "RIDER_VM_OPTIONS",
                scriptName = "rider",
            )

        val RUBY =
            IntellijLinterProperties(
                linter = Linters.RUBY,
                productInfoJsonCode = "RM",
                feedProductCode = "RM",
                vmOptionsEnv = "RUBYMINE_VM_OPTIONS",
                scriptName = "rubymine",
            )

        val CPP =
            IntellijLinterProperties(
                linter = Linters.CPP,
                productInfoJsonCode = "CL",
                feedProductCode = "CL",
                vmOptionsEnv = "CLION_VM_OPTIONS",
                scriptName = "clion",
            )

        val GO =
            IntellijLinterProperties(
                linter = Linters.GO,
                productInfoJsonCode = "GO",
                feedProductCode = "GO",
                vmOptionsEnv = "GOLAND_VM_OPTIONS",
                scriptName = "goland",
            )

        val RUST =
            IntellijLinterProperties(
                linter = Linters.RUST,
                productInfoJsonCode = "RR",
                feedProductCode = "RR",
                vmOptionsEnv = "RUSTROVER_VM_OPTIONS",
                scriptName = "rustrover",
            )

        val ALL =
            listOf(
                JVM,
                JVM_COMMUNITY,
                ANDROID,
                ANDROID_COMMUNITY,
                PHP,
                PYTHON,
                PYTHON_COMMUNITY,
                JS,
                DOTNET,
                RUBY,
                CPP,
                GO,
                RUST,
            )

        fun findByLinter(linter: Linter): IntellijLinterProperties? = ALL.find { it.linter == linter }

        fun findByProductInfoCode(code: String): IntellijLinterProperties? = ALL.find { it.productInfoJsonCode == code }
    }
}

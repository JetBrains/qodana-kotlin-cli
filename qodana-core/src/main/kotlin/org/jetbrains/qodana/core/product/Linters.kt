package org.jetbrains.qodana.core.product

object Linters {
    const val RELEASE_VERSION = "2025.3"
    const val SHORT_VERSION = "253"
    const val IS_RELEASED = false

    const val EAP_SUFFIX = "-EAP"
    const val RELEASE_VER = "release"
    const val EAP_VER = "eap"

    // Product codes
    const val QDJVMC = "QDJVMC"
    const val QDJVM = "QDJVM"
    const val QDAND = "QDAND"
    const val QDPHP = "QDPHP"
    const val QDPY = "QDPY"
    const val QDPYC = "QDPYC"
    const val QDJS = "QDJS"
    const val QDGO = "QDGO"
    const val QDNET = "QDNET"
    const val QDNETC = "QDNETC"
    const val QDANDC = "QDANDC"
    const val QDRST = "QDRST"
    const val QDRUBY = "QDRUBY"
    const val QDCLC = "QDCLC"
    const val QDCPP = "QDCPP"

    val JVM = Linter(
        name = "qodana-jvm",
        presentableName = "Qodana Ultimate for JVM",
        productCode = QDJVM,
        dockerImage = "jetbrains/qodana-jvm",
        supportsNative = true,
        isPaid = true,
        supportsFixes = true,
        eapOnly = false,
    )

    val JVM_COMMUNITY = Linter(
        name = "qodana-jvm-community",
        presentableName = "Qodana Community for JVM",
        productCode = QDJVMC,
        dockerImage = "jetbrains/qodana-jvm-community",
        supportsNative = true,
        isPaid = false,
        supportsFixes = false,
        eapOnly = false,
    )

    val ANDROID = Linter(
        name = "qodana-android",
        presentableName = "Qodana for Android",
        productCode = QDAND,
        dockerImage = "jetbrains/qodana-android",
        supportsNative = false,
        isPaid = true,
        supportsFixes = true,
        eapOnly = false,
    )

    val ANDROID_COMMUNITY = Linter(
        name = "qodana-jvm-android",
        presentableName = "Qodana Community for Android",
        productCode = QDANDC,
        dockerImage = "jetbrains/qodana-jvm-android",
        supportsNative = false,
        isPaid = false,
        supportsFixes = false,
        eapOnly = false,
    )

    val PHP = Linter(
        name = "qodana-php",
        presentableName = "Qodana for PHP",
        productCode = QDPHP,
        dockerImage = "jetbrains/qodana-php",
        supportsNative = true,
        isPaid = true,
        supportsFixes = true,
        eapOnly = false,
    )

    val PYTHON = Linter(
        name = "qodana-python",
        presentableName = "Qodana for Python",
        productCode = QDPY,
        dockerImage = "jetbrains/qodana-python",
        supportsNative = true,
        isPaid = true,
        supportsFixes = true,
        eapOnly = false,
    )

    val PYTHON_COMMUNITY = Linter(
        name = "qodana-python-community",
        presentableName = "Qodana Community for Python",
        productCode = QDPYC,
        dockerImage = "jetbrains/qodana-python-community",
        supportsNative = true,
        isPaid = false,
        supportsFixes = false,
        eapOnly = false,
    )

    val JS = Linter(
        name = "qodana-js",
        presentableName = "Qodana for JS",
        productCode = QDJS,
        dockerImage = "jetbrains/qodana-js",
        supportsNative = true,
        isPaid = true,
        supportsFixes = true,
        eapOnly = false,
    )

    val DOTNET = Linter(
        name = "qodana-dotnet",
        presentableName = "Qodana for .NET",
        productCode = QDNET,
        dockerImage = "jetbrains/qodana-dotnet",
        supportsNative = true,
        isPaid = true,
        supportsFixes = true,
        eapOnly = false,
    )

    val RUBY = Linter(
        name = "qodana-ruby",
        presentableName = "Qodana for Ruby",
        productCode = QDRUBY,
        dockerImage = "jetbrains/qodana-ruby",
        supportsNative = false,
        isPaid = true,
        supportsFixes = true,
        eapOnly = true,
    )

    val CPP = Linter(
        name = "qodana-cpp",
        presentableName = "Qodana for C/C++",
        productCode = QDCPP,
        dockerImage = "jetbrains/qodana-cpp",
        supportsNative = true,
        isPaid = true,
        supportsFixes = false,
        eapOnly = true,
    )

    val GO = Linter(
        name = "qodana-go",
        presentableName = "Qodana for Go",
        productCode = QDGO,
        dockerImage = "jetbrains/qodana-go",
        supportsNative = true,
        isPaid = true,
        supportsFixes = true,
        eapOnly = false,
    )

    val RUST = Linter(
        name = "qodana-rust",
        presentableName = "Qodana for Rust",
        productCode = QDRST,
        dockerImage = "jetbrains/qodana-rust",
        supportsNative = false,
        isPaid = true,
        supportsFixes = false,
        eapOnly = true,
    )

    val DOTNET_COMMUNITY = Linter(
        name = "qodana-cdnet",
        presentableName = "Qodana Community for .NET",
        productCode = QDNETC,
        dockerImage = "jetbrains/qodana-cdnet",
        supportsNative = false,
        isPaid = false,
        supportsFixes = false,
        eapOnly = true,
    )

    val CLANG = Linter(
        name = "qodana-clang",
        presentableName = "Qodana Community for C/C++",
        productCode = QDCLC,
        dockerImage = "jetbrains/qodana-clang",
        supportsNative = false,
        isPaid = false,
        supportsFixes = false,
        eapOnly = true,
    )

    // Order matters for detection
    val ALL: List<Linter> = listOf(
        JVM_COMMUNITY, JVM,
        ANDROID_COMMUNITY, ANDROID,
        PHP,
        PYTHON_COMMUNITY, PYTHON,
        JS,
        DOTNET_COMMUNITY, DOTNET,
        RUBY, CPP, GO, RUST,
        CLANG,
    )

    val ALL_FREE: List<Linter> = ALL.filter { !it.isPaid }
    val ALL_NATIVE: List<Linter> = ALL.filter { it.supportsNative }

    val LANGS_TO_LINTERS: Map<String, List<Linter>> = mapOf(
        "Java" to listOf(JVM, JVM_COMMUNITY, ANDROID, ANDROID_COMMUNITY),
        "Kotlin" to listOf(JVM, JVM_COMMUNITY, ANDROID, ANDROID_COMMUNITY),
        "PHP" to listOf(PHP),
        "Python" to listOf(PYTHON, PYTHON_COMMUNITY),
        "JavaScript" to listOf(JS),
        "TypeScript" to listOf(JS),
        "Go" to listOf(GO),
        "C#" to listOf(DOTNET, DOTNET_COMMUNITY),
        "F#" to listOf(DOTNET),
        "Visual Basic .NET" to listOf(DOTNET, DOTNET_COMMUNITY),
        "C" to listOf(CPP, CLANG, DOTNET),
        "C++" to listOf(CPP, CLANG, DOTNET),
        "Ruby" to listOf(RUBY),
        "Rust" to listOf(RUST),
    )

    fun findByProductCode(code: String): Linter? {
        val normalized = code.removeSuffix(EAP_SUFFIX)
        return ALL.find { it.productCode == normalized }
    }

    fun findByName(name: String): Linter? {
        val normalized = name.removeSuffix(EAP_SUFFIX)
        return ALL.find { it.name == normalized }
    }

    fun findByDockerImage(image: String): Linter? {
        // Strip protocol prefixes
        val cleaned = image
            .removePrefix("https://")
            .removePrefix("http://")
        return ALL.find { cleaned.startsWith(it.dockerImage) }
    }

    fun getVersionBranch(version: String): String {
        // "2025.3" → "253", "2024.1" → "241"
        val parts = version.split(".")
        if (parts.size < 2) return ""
        val year = parts[0]
        val minor = parts[1]
        return if (year.length >= 4) "${year.substring(2)}$minor" else ""
    }

    fun getScriptSuffix(): String {
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> ".bat"
            else -> ".sh"
        }
    }

    fun isRuby(linter: Linter): Boolean = linter.productCode == QDRUBY
}

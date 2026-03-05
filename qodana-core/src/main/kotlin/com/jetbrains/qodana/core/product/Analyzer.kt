package com.jetbrains.qodana.core.product

sealed interface Analyzer {
    val linter: Linter
    val isEap: Boolean

    data class Native(override val linter: Linter, override val isEap: Boolean) : Analyzer
    data class Docker(override val linter: Linter, val image: String, override val isEap: Boolean = false) : Analyzer
}

fun Linter.nativeAnalyzer(): Analyzer = Analyzer.Native(this, isEap = eapOnly)
fun Linter.dockerAnalyzer(): Analyzer = Analyzer.Docker(this, image = image())

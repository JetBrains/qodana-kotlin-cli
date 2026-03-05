package com.jetbrains.qodana.clang

fun main(args: Array<String>) {
    println("qodana-clang ${Version.VALUE}")
}

object Version {
    const val VALUE = "0.1.0-dev"
}

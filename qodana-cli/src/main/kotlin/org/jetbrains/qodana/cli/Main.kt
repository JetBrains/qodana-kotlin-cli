package org.jetbrains.qodana.cli

fun main(args: Array<String>) {
    println("qodana ${Version.VALUE}")
}

object Version {
    const val VALUE = "0.1.0-dev"
}

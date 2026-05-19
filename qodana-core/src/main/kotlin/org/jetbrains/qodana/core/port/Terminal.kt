package org.jetbrains.qodana.core.port

interface Terminal {
    fun interface SpinnerHandle {
        fun update(message: String)
    }

    fun print(message: String)

    fun println(message: String)

    fun error(message: String)

    fun info(message: String)

    fun warn(message: String)

    fun debug(message: String)

    fun <T> spinner(
        message: String,
        action: () -> T,
    ): T

    suspend fun <T> spinnerWithUpdates(
        message: String,
        action: suspend (SpinnerHandle) -> T,
    ): T =
        spinner(message) {
            kotlinx.coroutines.runBlocking {
                action(SpinnerHandle { })
            }
        }

    fun prompt(
        message: String,
        default: String? = null,
    ): String

    fun select(
        message: String,
        choices: List<String>,
    ): String

    val isInteractive: Boolean
    var isCi: Boolean

    fun setRedactedTokens(tokens: Set<String>)
}

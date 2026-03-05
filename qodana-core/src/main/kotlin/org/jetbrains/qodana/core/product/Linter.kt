package org.jetbrains.qodana.core.product

data class Linter(
    val name: String,
    val presentableName: String,
    val productCode: String,
    val dockerImage: String,
    val supportsNative: Boolean,
    val isPaid: Boolean,
    val supportsFixes: Boolean,
    val eapOnly: Boolean,
) {
    fun image(): String {
        val tag = if (!Linters.IS_RELEASED || eapOnly) {
            "${Linters.RELEASE_VERSION}-eap"
        } else {
            Linters.RELEASE_VERSION
        }
        return "$dockerImage:$tag"
    }
}

package org.jetbrains.qodana.core.model

data class AuthContext(
    val token: String?,
    val endpoint: String,
    val licenseOnlyToken: String? = null,
) {
    val hasToken: Boolean get() = !token.isNullOrBlank()
    val isLicenseOnly: Boolean get() = !licenseOnlyToken.isNullOrBlank() && token.isNullOrBlank()
}

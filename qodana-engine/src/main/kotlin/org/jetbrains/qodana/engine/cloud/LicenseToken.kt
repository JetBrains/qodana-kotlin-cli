package org.jetbrains.qodana.engine.cloud

data class LicenseToken(
    val token: String,
    val licenseOnly: Boolean,
) {
    val isAllowedToSendReports: Boolean
        get() = !licenseOnly && token.isNotEmpty()

    val isAllowedToSendFus: Boolean
        get() = !licenseOnly

    companion object {
        val EMPTY = LicenseToken(token = "", licenseOnly = false)

        fun resolve(cloudToken: String?, licenseOnlyToken: String?): LicenseToken {
            return when {
                !cloudToken.isNullOrEmpty() -> LicenseToken(token = cloudToken, licenseOnly = false)
                !licenseOnlyToken.isNullOrEmpty() -> LicenseToken(token = licenseOnlyToken, licenseOnly = true)
                else -> EMPTY
            }
        }

        /**
         * Determines if a cloud token is required for the given analyzer configuration.
         * Token is required if:
         * - A token is already provided, OR
         * - A license-only token is set, OR
         * - The analyzer is paid, non-EAP, and no license env var is set.
         */
        fun isCloudTokenRequired(
            qodanaToken: String?,
            licenseOnlyToken: String?,
            licenseEnv: String?,
            isPaid: Boolean,
            isEap: Boolean,
        ): Boolean {
            if (!qodanaToken.isNullOrEmpty()) return true
            if (!licenseOnlyToken.isNullOrEmpty()) return true
            return licenseEnv.isNullOrEmpty() && isPaid && !isEap
        }
    }
}

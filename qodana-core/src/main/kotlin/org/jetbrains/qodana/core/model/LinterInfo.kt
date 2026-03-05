package org.jetbrains.qodana.core.model

import org.jetbrains.qodana.core.product.Linters

data class LinterInfo(
    val productCode: String,
    val linterVersion: String = "",
    val licensePlan: String = "",
) {
    private val majorVersionRegex = Regex("""\b(\d+\.\d+)""")

    /**
     * Extracts major.minor version from linterVersion string.
     * Falls back to Linters.RELEASE_VERSION if not parseable.
     */
    fun getMajorVersion(): String {
        val match = majorVersionRegex.find(linterVersion)
        return match?.groupValues?.get(1) ?: Linters.RELEASE_VERSION
    }

    /**
     * Checks if the license plan is community tier.
     */
    fun isCommunity(): Boolean = licensePlan.equals("COMMUNITY", ignoreCase = true)
}

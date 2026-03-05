package org.jetbrains.qodana.engine.cloud

import org.jetbrains.qodana.core.model.ExitCode
import org.jetbrains.qodana.core.model.QodanaError
import org.jetbrains.qodana.core.product.Linter
import org.jetbrains.qodana.core.product.Linters

data class LicenseSetupResult(
    val licenseKey: String = "",
    val projectIdHash: String = "",
    val organisationIdHash: String = "",
)

object LicenseSetup {

    fun allCommunityNames(): String {
        return Linters.ALL
            .filter { !it.isPaid }
            .joinToString(", ") { "\"${it.presentableName}\"" }
    }

    suspend fun setupLicenseAndProjectHash(
        linter: Linter,
        licenseToken: LicenseToken,
        validator: LicenseValidator,
        existingLicense: String? = null,
    ): Result<LicenseSetupResult> {
        // If license already provided via env, use it
        if (!existingLicense.isNullOrEmpty()) {
            return Result.success(LicenseSetupResult(licenseKey = existingLicense))
        }

        // Community linters don't need a license
        if (!linter.isPaid) {
            return Result.success(LicenseSetupResult())
        }

        val token = licenseToken.token

        // EAP builds: warn if no token but don't fail
        if (linter.eapOnly && token.isEmpty()) {
            return Result.success(LicenseSetupResult())
        }

        // Release builds: require token for paid linters
        if (token.isEmpty()) {
            return Result.failure(
                QodanaErrorException(
                    QodanaError.Auth(
                        "No token provided. Paid linters require a valid Qodana token. " +
                            "Free linters: ${allCommunityNames()}"
                    )
                )
            )
        }

        // Fetch license data from cloud
        val licenseData = validator.validate(token).getOrElse { e ->
            return Result.failure(e)
        }

        // Community plan can't run paid linters
        if (licenseData.licensePlan.equals("COMMUNITY", ignoreCase = true)) {
            return Result.failure(
                QodanaErrorException(
                    QodanaError.Auth(
                        "Community license plan does not support paid linters. " +
                            "Free linters: ${allCommunityNames()}"
                    )
                )
            )
        }

        if (licenseData.licenseKey.isEmpty()) {
            return Result.failure(
                QodanaErrorException(QodanaError.Auth("License key is empty"))
            )
        }

        return Result.success(
            LicenseSetupResult(
                licenseKey = licenseData.licenseKey,
                projectIdHash = licenseData.projectIdHash,
                organisationIdHash = licenseData.organisationIdHash,
            )
        )
    }
}

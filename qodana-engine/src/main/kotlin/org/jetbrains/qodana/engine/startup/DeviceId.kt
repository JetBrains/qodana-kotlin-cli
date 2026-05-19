package org.jetbrains.qodana.engine.startup

import java.security.MessageDigest

object DeviceId {
    data class DeviceIdSalt(
        val deviceId: String,
        val salt: String,
    )

    fun getDeviceIdSalt(
        remoteUrl: String = "",
        envSalt: String = System.getenv("SALT") ?: "",
        envDeviceId: String = System.getenv("DEVICEID") ?: "",
    ): DeviceIdSalt {
        var salt = envSalt
        var deviceId = envDeviceId

        if (salt.isEmpty() || deviceId.isEmpty()) {
            val hash =
                if (remoteUrl.isNotEmpty()) {
                    md5Hex("1n1T-\$@Lt-$remoteUrl")
                } else {
                    "00000000000000000000000000000000"
                }

            if (salt.isEmpty()) {
                salt = md5Hex("\$eC0nd-\$@Lt-$hash")
            }
            if (deviceId.isEmpty()) {
                deviceId =
                    "200820300000000-${hash.substring(0, 4)}-${hash.substring(4, 8)}-${hash.substring(8, 12)}-${hash.substring(12, 24)}"
            }
        }

        return DeviceIdSalt(deviceId, salt)
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

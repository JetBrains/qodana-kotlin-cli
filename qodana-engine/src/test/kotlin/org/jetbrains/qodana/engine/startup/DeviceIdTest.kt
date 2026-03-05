package org.jetbrains.qodana.engine.startup

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DeviceIdTest {

    @Test
    fun `empty remote url generates default device id`() {
        val result = DeviceId.getDeviceIdSalt(
            remoteUrl = "",
            envSalt = "",
            envDeviceId = "",
        )
        assertEquals("200820300000000-0000-0000-0000-000000000000", result.deviceId)
        assertEquals("0229f593f62e84ad29a64cebb6a9b861", result.salt)
    }

    @Test
    fun `remote url generates deterministic device id`() {
        val result = DeviceId.getDeviceIdSalt(
            remoteUrl = "ssh://git@git/repo",
            envSalt = "",
            envDeviceId = "",
        )
        assertEquals("200820300000000-a294-0dd1-57f5-9f44b322ff64", result.deviceId)
        assertEquals("e5c8900956f0df2f18f827245f47f04a", result.salt)
    }

    @Test
    fun `env vars override computed values`() {
        val result = DeviceId.getDeviceIdSalt(
            remoteUrl = "ssh://git@git/repo",
            envSalt = "salt",
            envDeviceId = "device",
        )
        assertEquals("device", result.deviceId)
        assertEquals("salt", result.salt)
    }

    @Test
    fun `same remote url produces same result`() {
        val r1 = DeviceId.getDeviceIdSalt(remoteUrl = "https://github.com/user/repo.git")
        val r2 = DeviceId.getDeviceIdSalt(remoteUrl = "https://github.com/user/repo.git")
        assertEquals(r1, r2)
    }
}

package org.jetbrains.qodana.engine.startup

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class XmlConfigTest {

    @Test
    fun `jdkTableXml contains required elements`() {
        val jdkPath = "/path/to/jdk"
        val result = XmlConfig.jdkTableXml(jdkPath)

        assertTrue(result.contains("<application>"))
        assertTrue(result.contains("ProjectJdkTable"))
        assertTrue(result.contains(jdkPath))
        assertTrue(result.contains("<homePath"))
        assertTrue(result.startsWith("<application>"))
    }

    @Test
    fun `androidProjectDefaultXml contains required elements`() {
        val sdkPath = "/path/to/android/sdk"
        val result = XmlConfig.androidProjectDefaultXml(sdkPath)

        assertTrue(result.contains("<application>"))
        assertTrue(result.contains("android.sdk.path"))
        assertTrue(result.contains(sdkPath))
        assertTrue(result.contains("ProjectManager"))
    }

    @Test
    fun `securityXml contains PasswordSafe KeePass config`() {
        assertTrue(XmlConfig.SECURITY_XML.contains("PasswordSafe"))
        assertTrue(XmlConfig.SECURITY_XML.contains("KEEPASS"))
    }
}

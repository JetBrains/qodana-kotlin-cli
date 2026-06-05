package org.jetbrains.qodana.core

import org.jetbrains.qodana.core.model.ExitCode
import java.io.DataInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the JDK 21 -> 25 toolchain migration (QD-14917). Fails on a JDK-21 toolchain
 * and passes only on JDK 25, so it cannot be satisfied without actually moving the build.
 *
 * Two independent signals:
 *  1. Runtime: the Gradle `test` task forks the toolchain JVM, so this test's own
 *     `java.specification.version` reflects the configured toolchain.
 *  2. Bytecode: a first-party production class (`ExitCode`) must be compiled to the
 *     Java [JAVA_FEATURE] class-file major version. Per JVMS the class-file major
 *     version is `44 + featureVersion` (Java 21 = 65, Java 25 = 69).
 */
class Jdk25ToolchainTest {
    @Test
    fun `toolchain JVM is JDK 25`() {
        assertEquals(JAVA_FEATURE.toString(), System.getProperty("java.specification.version"))
    }

    @Test
    fun `production bytecode targets Java 25`() {
        val clazz = ExitCode::class.java
        val resourcePath = "/" + clazz.name.replace('.', '/') + ".class"
        val stream =
            requireNotNull(clazz.getResourceAsStream(resourcePath)) {
                "Class file for ${clazz.name} not found on the test classpath ($resourcePath)"
            }
        // Class file layout (JVMS §4.1): u4 magic, u2 minor_version, u2 major_version.
        val header = stream.use { ByteArray(8).also { buf -> DataInputStream(it).readFully(buf) } }
        val magic =
            ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
        assertEquals(0xCAFEBABE.toInt(), magic, "Not a class file")
        val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        assertEquals(EXPECTED_CLASS_FILE_MAJOR, major)
    }

    private companion object {
        const val JAVA_FEATURE = 25
        const val EXPECTED_CLASS_FILE_MAJOR = 44 + JAVA_FEATURE // JVMS: Java 25 -> 69
    }
}

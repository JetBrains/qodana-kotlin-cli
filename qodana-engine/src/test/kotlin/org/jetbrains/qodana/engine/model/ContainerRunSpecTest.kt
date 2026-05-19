package org.jetbrains.qodana.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainerRunSpecTest {
    @Test
    fun `default spec has empty collections`() {
        val spec = ContainerRunSpec(image = "alpine:latest")
        assertTrue(spec.mounts.isEmpty())
        assertTrue(spec.env.isEmpty())
        assertTrue(spec.entrypoint.isEmpty())
        assertTrue(spec.cmd.isEmpty())
        assertTrue(spec.labels.isEmpty())
        assertTrue(spec.capAdd.isEmpty())
        assertTrue(spec.securityOpts.isEmpty())
        assertTrue(spec.exposedPorts.isEmpty())
        assertTrue(spec.portBindings.isEmpty())
        assertFalse(spec.tty)
        assertFalse(spec.autoRemove)
    }

    @Test
    fun `mount spec read-only default`() {
        val mount = MountSpec(hostPath = "/host", containerPath = "/container")
        assertFalse(mount.readOnly)
    }

    @Test
    fun `resource limits default to null`() {
        val limits = ResourceLimits()
        assertEquals(null, limits.memoryBytes)
        assertEquals(null, limits.cpuCount)
    }

    @Test
    fun `spec with dotnet capabilities`() {
        val spec =
            ContainerRunSpec(
                image = "jetbrains/qodana-dotnet:latest",
                capAdd = listOf("SYS_PTRACE"),
                securityOpts = listOf("seccomp=unconfined"),
            )
        assertEquals(listOf("SYS_PTRACE"), spec.capAdd)
        assertEquals(listOf("seccomp=unconfined"), spec.securityOpts)
    }

    @Test
    fun `spec with port bindings`() {
        val spec =
            ContainerRunSpec(
                image = "alpine:latest",
                portBindings = mapOf(5005 to 5005),
                exposedPorts = listOf(5005),
            )
        assertEquals(5005, spec.portBindings[5005])
        assertEquals(listOf(5005), spec.exposedPorts)
    }

    @Test
    fun `container exit status`() {
        val success = ContainerExitStatus(exitCode = 0)
        assertEquals(0, success.exitCode)
        assertFalse(success.oomKilled)

        val oom = ContainerExitStatus(exitCode = 137, oomKilled = true)
        assertEquals(137, oom.exitCode)
        assertTrue(oom.oomKilled)
    }
}

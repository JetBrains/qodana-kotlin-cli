package org.jetbrains.qodana.engine.model

import org.jetbrains.qodana.engine.env.RuntimeEnvironment
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ExecutionProfileResolverTest {

    @Test
    fun `native target resolves to native profile in host runtime`() {
        val resolver = ExecutionProfileResolver { RuntimeEnvironment.HOST }

        val profile = resolver.resolve(RequestedExecutionTarget.NATIVE)

        assertEquals(NativeExecutionProfile, profile)
    }

    @Test
    fun `native target resolves to native profile in docker runtime`() {
        val resolver = ExecutionProfileResolver { RuntimeEnvironment.IN_DOCKER }

        val profile = resolver.resolve(RequestedExecutionTarget.NATIVE)

        assertEquals(NativeExecutionProfile, profile)
    }

    @Test
    fun `container target resolves to docker launcher profile on host`() {
        val resolver = ExecutionProfileResolver { RuntimeEnvironment.HOST }

        val profile = resolver.resolve(RequestedExecutionTarget.CONTAINER)

        assertEquals(DockerLauncherExecutionProfile, profile)
    }

    @Test
    fun `container target resolves to in docker profile in docker runtime`() {
        val resolver = ExecutionProfileResolver { RuntimeEnvironment.IN_DOCKER }

        val profile = resolver.resolve(RequestedExecutionTarget.CONTAINER)

        assertEquals(InDockerExecutionProfile, profile)
    }
}

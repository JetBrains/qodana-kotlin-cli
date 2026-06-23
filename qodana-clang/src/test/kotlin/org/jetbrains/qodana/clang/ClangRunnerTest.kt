package org.jetbrains.qodana.clang

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClangRunnerTest {
    // Production headers are single tokens (CompileCommands.kt:98), not "-isystem" + path pairs.
    private val input = FileWithHeaders(file = "src/main.cpp", headers = listOf("--extra-arg=-isystem/usr/include"))
    private val out = Path.of("results", "tmp", "0.sarif.json")

    @Test
    fun `omits empty checks and forwards config-file in order`() {
        val args =
            buildClangTidyArgs(
                checks = "",
                configFile = "/p/_clang-tidy",
                compileCommands = "/p/compile_commands.json",
                input = input,
                sarifOutput = out,
                clangArgs = "",
            )
        assertEquals(
            listOf(
                "--config-file=/p/_clang-tidy",
                "-p", "/p/compile_commands.json",
                "--export-sarif", out.toString(),
                "--extra-arg=-isystem/usr/include",
                "src/main.cpp",
                "--quiet",
            ),
            args,
        )
    }

    @Test
    fun `includes checks and omits config-file when null`() {
        val args = buildClangTidyArgs("--checks=-*,bugprone-*", null, "/p/cc.json", input, out, "")
        assertEquals("--checks=-*,bugprone-*", args.first())
        assertFalse(args.any { it.startsWith("--config-file=") })
    }

    @Test
    fun `appends split clang args last`() {
        val args = buildClangTidyArgs("--checks=*", null, "/p/cc.json", input, out, "--extra-arg=-std=c++20")
        assertTrue(args.last() == "--extra-arg=-std=c++20")
    }
}

package org.jetbrains.qodana.core.test

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsNativeDepsAssertionTest {
    @Test
    fun `flags every VC++ redistributable runtime DLL`() {
        val imports =
            listOf(
                "VCRUNTIME140.dll",
                "vcruntime140_1.dll",
                "MSVCP140.dll",
                "concrt140.dll",
                "msvcr120.dll",
                "vcomp140.dll",
                "mfc140u.dll",
                "mfcm140.dll",
                "vcamp140.dll",
                "atl140.dll",
                "vccorlib140.dll",
            )
        assertEquals(imports, forbiddenVcRuntimeImports(imports))
    }

    @Test
    fun `allows UCRT and system DLLs`() {
        val allowed =
            listOf(
                "ucrtbase.dll",
                "api-ms-win-crt-heap-l1-1-0.dll",
                "api-ms-win-crt-runtime-l1-1-0.dll",
                "KERNEL32.dll",
                "ntdll.dll",
                "ADVAPI32.dll",
                "WS2_32.dll",
                "statlib.dll",
            )
        assertEquals(emptyList(), forbiddenVcRuntimeImports(allowed))
    }

    @Test
    fun `preserves input order and is case-insensitive`() {
        val imports = listOf("kernel32.dll", "MsVcP140.dll", "ucrtbase.dll", "vcruntime140.dll")
        assertEquals(listOf("MsVcP140.dll", "vcruntime140.dll"), forbiddenVcRuntimeImports(imports))
    }
}

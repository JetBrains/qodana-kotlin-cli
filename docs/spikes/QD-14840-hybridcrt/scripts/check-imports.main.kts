#!/usr/bin/env kotlin
// Cross-check script: print the import + delay-load-import DLL names of a Windows PE binary using PortEx.
// Used in Task 3.4 of the QD-14840 spike runbook to confirm dumpbin output matches PortEx (the library
// used by Phase A's NativeWindowsDepsTest).
//
// Usage: kotlin check-imports.main.kts <path-to-exe>

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("io.github.struppigel:portex_2.12:5.0.6")

import io.github.struppigel.parser.PELoader
import java.io.File

require(args.size == 1) { "Usage: kotlin check-imports.main.kts <path-to-exe>" }
val exe = File(args[0])
require(exe.isFile) { "File does not exist: $exe" }

val data = PELoader.loadPE(exe)
val all = (data.loadImports() + data.loadDelayLoadImports())
    .map { it.name }
    .distinct()
    .sorted()
println(all.joinToString(System.lineSeparator()))

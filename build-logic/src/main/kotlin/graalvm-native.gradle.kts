plugins {
    id("org.graalvm.buildtools.native")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set(project.name)
            fallback.set(false)
            buildArgs.addAll(
                "--no-fallback",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time",
            )
        }
    }
    toolchainDetection.set(false)
}

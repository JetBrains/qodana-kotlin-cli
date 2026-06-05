plugins {
    id("dev.detekt")
}

configure<dev.detekt.gradle.extensions.DetektExtension> {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yaml"))
    // Per-subproject baseline so concurrent detektBaselineMain runs don't clobber each other.
    baseline = file("config/detekt/baseline.xml")
    parallel = true
}

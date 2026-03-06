import org.gradle.api.tasks.testing.Test

// Root project — no plugins applied here

subprojects {
    tasks.withType<Test>().configureEach {
        val qodanaToken = System.getenv("QODANA_TOKEN")
        val licenseOnlyToken = System.getenv("QODANA_LICENSE_ONLY_TOKEN") ?: qodanaToken

        if (!qodanaToken.isNullOrBlank()) {
            environment("QODANA_TOKEN", qodanaToken)
        }
        if (!licenseOnlyToken.isNullOrBlank()) {
            environment("QODANA_LICENSE_ONLY_TOKEN", licenseOnlyToken)
        }
    }

    tasks.register<Test>("parityTest") {
        group = "verification"
        description = "Runs tests with parity-oriented environment (no gated skips by token/container flags)."

        val testTask = tasks.named<Test>("test").get()
        testClassesDirs = testTask.testClassesDirs
        classpath = testTask.classpath
        useJUnitPlatform()
        shouldRunAfter(testTask)

        environment("QODANA_TEST_CONTAINER", System.getenv("QODANA_TEST_CONTAINER") ?: "1")
        val qodanaToken = System.getenv("QODANA_TOKEN")
        val licenseOnlyToken = System.getenv("QODANA_LICENSE_ONLY_TOKEN") ?: qodanaToken
        if (!qodanaToken.isNullOrBlank()) {
            environment("QODANA_TOKEN", qodanaToken)
        }
        if (!licenseOnlyToken.isNullOrBlank()) {
            environment("QODANA_LICENSE_ONLY_TOKEN", licenseOnlyToken)
        }
    }
}

tasks.register("parityTest") {
    group = "verification"
    description = "Runs parityTest in all modules."
    dependsOn(subprojects.map { it.tasks.named("parityTest") })
}

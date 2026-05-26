/**
 * The release shape for a binary module — chooses between archived (Cli) and raw-binary (Tool) outputs.
 *
 * Public so that consumer modules can configure their `qodanaRelease { kind.set(QodanaReleaseKind.Cli) }`
 * extension block. Kept at the top level (no package) to match the placement of the precompiled script
 * plugin `qodana-release.gradle.kts`, so the same accessor scope applies.
 */
enum class QodanaReleaseKind { Cli, Tool }

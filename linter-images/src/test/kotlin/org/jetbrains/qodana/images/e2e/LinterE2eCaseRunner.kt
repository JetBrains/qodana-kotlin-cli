package org.jetbrains.qodana.images.e2e

import com.jetbrains.qodana.sarif.SarifUtil
import com.jetbrains.qodana.sarif.model.SarifReport
import org.jetbrains.qodana.images.process.CommandResult
import org.jetbrains.qodana.images.process.CommandRunner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Outcome of one `(case, variant)` container run: the raw [CommandResult] of `docker run`, the
 * parsed [SarifReport] (null iff `qodana.sarif.json` was absent OR failed to parse — distinguish via
 * [sarifParseError]), the parse error if the file existed but did not parse, the `log/idea.log` text
 * (null iff absent), and the temp results dir (kept for failure diagnostics — the discovery test
 * lists it on a missing-SARIF failure).
 */
data class CaseRunResult(
    val command: CommandResult,
    val report: SarifReport?,
    val sarifParseError: Throwable?,
    val ideaLog: String?,
    val resultsDir: Path,
)

/**
 * Drives ONE fixture `(case, variant)` end to end against a prebuilt `<image>:dev` container.
 *
 * Per variant it: copies `project/` to a fresh temp tree (`toRealPath()` first, then
 * `copyRecursively`, mirroring `NativeSmokeTest` — this dodges the macOS `/var`→`/private/var`
 * symlink mismatch that would make the CLI emit a navigate-up `--project-dir`); overlays the
 * variant's `qodanaYaml` onto `project/qodana.yaml`; materializes the requested `gitState` by
 * shelling `git` through the injected [CommandRunner]; creates world-writable temp `results/` +
 * `cache/` (the image runs as UID 1000 and must write both); builds the `docker run` argv via
 * [DockerRunPlanner]; runs it through [CommandRunner]; and parses the produced SARIF + idea.log.
 *
 * Pure orchestration — no assertions. [LinterE2eTest] owns exit-code / SARIF / log checks. The
 * [CommandRunner] is injected so the docker invocation stays the real [ProcessCommandRunner] in
 * CI while remaining a seam; `git` is run through the SAME runner.
 */
class LinterE2eCaseRunner(
    private val commandRunner: CommandRunner,
    private val tempRoot: Path,
) {
    /**
     * @param manifest the parsed `expected.json`.
     * @param caseDir the case directory containing `project/` and the variant `qodana.*.yaml` files.
     * @param variant the variant to run (its `qodanaYaml`/`gitState`).
     * @param imageTag the prebuilt image tag, e.g. `qodana-jvm:dev`.
     */
    fun run(
        manifest: ExpectedManifest,
        caseDir: Path,
        variant: Variant,
        imageTag: String,
    ): CaseRunResult {
        val work = Files.createDirectories(tempRoot.resolve("${manifest.case}-${variant.id}")).toRealPath()
        val projectDir = prepareProject(caseDir, variant, work)
        applyGitState(projectDir, variant.gitState)

        val resultsDir = worldWritableDir(work.resolve("results"))
        val cacheDir = worldWritableDir(work.resolve("cache"))

        val argv = DockerRunPlanner.dockerArgs(imageTag, projectDir, resultsDir, cacheDir, manifest.run)
        val command = commandRunner.run(argv)

        val sarifPath = resultsDir.resolve("qodana.sarif.json")
        // An ABSENT file is a clean null (e.g. the android missing-SDK twin, QD-2179, fails before
        // writing one). A PRESENT file is parsed; BOTH a thrown exception (malformed JSON) AND a null
        // result (qodana-sarif/Gson returns null for empty / `[]` / `[null]` content WITHOUT throwing)
        // are surfaced via [CaseRunResult.sarifParseError], so a SARIF-asserting case fails LOUDLY with
        // an accurate cause — never the misleading "no SARIF produced" (spec Error-handling section).
        val report: SarifReport?
        val sarifParseError: Throwable?
        if (sarifPath.isRegularFile()) {
            val parsed = runCatching { SarifUtil.readReport(sarifPath) }
            report = parsed.getOrNull()
            sarifParseError =
                parsed.exceptionOrNull()
                    ?: if (report == null) {
                        IllegalStateException(
                            "qodana.sarif.json is present at $sarifPath but parsed to null " +
                                "(empty / '[]' / '[null]' — the scan produced no SARIF document)",
                        )
                    } else {
                        null
                    }
        } else {
            report = null
            sarifParseError = null
        }
        val ideaLogPath = resultsDir.resolve("log").resolve("idea.log")
        val ideaLog = if (ideaLogPath.isRegularFile()) ideaLogPath.readText() else null

        return CaseRunResult(command, report, sarifParseError, ideaLog, resultsDir)
    }

    // Copy project/ then overlay the variant's qodana.yaml -----------------------------------------

    private fun prepareProject(
        caseDir: Path,
        variant: Variant,
        work: Path,
    ): Path {
        val source = caseDir.resolve("project")
        check(source.isRegularFile().not() && source.exists()) {
            "case project tree missing: expected a directory at $source"
        }
        // toRealPath() BEFORE copy so the destination is canonical (see class kdoc / NativeSmokeTest).
        val projectDir = Files.createDirectories(work.resolve("project")).toRealPath()
        source.toFile().copyRecursively(projectDir.toFile(), overwrite = true)

        // qodanaYaml is a path RELATIVE TO the project tree (the override files ship inside
        // project/, e.g. project/qodana.control.yaml), so resolve it against the copied projectDir,
        // NOT caseDir. We overwrite project/qodana.yaml with the chosen variant file.
        variant.qodanaYaml?.let { yamlName ->
            val override = projectDir.resolve(yamlName)
            check(override.isRegularFile()) {
                "variant '${variant.id}' references qodanaYaml '$yamlName' but $override is not a file"
            }
            override.copyTo(projectDir.resolve("qodana.yaml"), overwrite = true)
        }
        return projectDir
    }

    // gitState: none | init-no-head | clean --------------------------------------------------------

    private fun applyGitState(
        projectDir: Path,
        gitState: String,
    ) {
        when (gitState) {
            "none" -> {
                // Ensure no .git leaked in via copyRecursively.
                val dotGit = projectDir.resolve(".git")
                if (dotGit.exists()) dotGit.toFile().deleteRecursively()
            }
            "init-no-head" -> {
                git(projectDir, "init")
            }
            "clean" -> {
                git(projectDir, "init")
                // Local identity so commit doesn't depend on the host's global git config.
                git(projectDir, "config", "user.email", "e2e@qodana.test")
                git(projectDir, "config", "user.name", "qodana-e2e")
                git(projectDir, "add", "-A")
                git(projectDir, "commit", "-m", "fixture", "--no-gpg-sign")
            }
            else -> error("unknown gitState '$gitState' (expected none | init-no-head | clean)")
        }
    }

    private fun git(
        workDir: Path,
        vararg args: String,
    ) {
        val result = commandRunner.run(listOf("git", *args), workDir)
        check(result.isSuccess) {
            "git ${args.joinToString(" ")} failed in $workDir (exit ${result.exitCode}): ${result.stderr}"
        }
    }

    // Mount perms: image runs UID 1000, so results/ + cache/ must be world-writable -----------------

    private fun worldWritableDir(dir: Path): Path {
        val created = Files.createDirectories(dir).toRealPath()
        // a+rwX so the UID-1000 container can write results/cache. Swallow ONLY the non-POSIX case
        // (the arm64 macOS dev-box temp FS): a real chmod failure on CI (Linux/POSIX) MUST propagate,
        // otherwise it surfaces later as a baffling "no SARIF produced".
        try {
            Files.setPosixFilePermissions(created, PosixFilePermissions.fromString("rwxrwxrwx"))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem; dev-box-only. The container's mount path on CI is POSIX.
        }
        return created
    }
}

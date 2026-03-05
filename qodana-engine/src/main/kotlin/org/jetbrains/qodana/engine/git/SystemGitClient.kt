package org.jetbrains.qodana.engine.git

import org.jetbrains.qodana.core.model.ProcessSpec
import org.jetbrains.qodana.engine.port.GitClient
import org.jetbrains.qodana.core.port.ProcessRunner
import java.nio.file.Path

class SystemGitClient(private val processRunner: ProcessRunner) : GitClient {

    override suspend fun revParse(workDir: Path, ref: String): Result<String> =
        git(workDir, "rev-parse", ref).map { it.trim() }

    override suspend fun checkout(workDir: Path, ref: String): Result<Unit> =
        git(workDir, "checkout", ref).map { }

    override suspend fun diff(workDir: Path, startRef: String?, endRef: String?): Result<String> {
        val args = buildList {
            add("diff")
            add("--name-only")
            startRef?.let { add(it) }
            endRef?.let { add(it) }
        }
        return git(workDir, *args.toTypedArray())
    }

    override suspend fun log(workDir: Path, format: String, maxCount: Int?): Result<String> {
        val args = buildList {
            add("log")
            add("--format=$format")
            maxCount?.let { add("--max-count=$it") }
        }
        return git(workDir, *args.toTypedArray())
    }

    override suspend fun branch(workDir: Path): Result<String> =
        git(workDir, "branch", "--show-current").map { it.trim() }

    override suspend fun remoteUrl(workDir: Path): Result<String> =
        git(workDir, "remote", "get-url", "origin").map { it.trim() }

    override suspend fun reset(workDir: Path, ref: String, hard: Boolean): Result<Unit> {
        val args = buildList {
            add("reset")
            if (hard) add("--hard")
            add(ref)
        }
        return git(workDir, *args.toTypedArray()).map { }
    }

    override suspend fun fetch(workDir: Path, remote: String?, ref: String?, depth: Int?): Result<Unit> {
        val args = buildList {
            add("fetch")
            depth?.let { add("--depth=$it") }
            remote?.let { add(it) }
            ref?.let { add(it) }
        }
        return git(workDir, *args.toTypedArray()).map { }
    }

    override suspend fun isGitRepo(workDir: Path): Boolean =
        git(workDir, "rev-parse", "--is-inside-work-tree").isSuccess

    override suspend fun currentBranch(workDir: Path): Result<String> =
        git(workDir, "rev-parse", "--abbrev-ref", "HEAD").map { it.trim() }

    override suspend fun currentRevision(workDir: Path): Result<String> =
        git(workDir, "rev-parse", "HEAD").map { it.trim() }

    override suspend fun clean(workDir: Path, force: Boolean, directories: Boolean): Result<Unit> {
        val args = buildList {
            add("clean")
            if (force) add("-f")
            if (directories) add("-d")
        }
        return git(workDir, *args.toTypedArray()).map { }
    }

    override suspend fun submoduleUpdate(workDir: Path, init: Boolean, recursive: Boolean): Result<Unit> {
        val args = buildList {
            add("submodule")
            add("update")
            if (init) add("--init")
            if (recursive) add("--recursive")
        }
        return git(workDir, *args.toTypedArray()).map { }
    }

    private suspend fun git(workDir: Path, vararg args: String): Result<String> {
        val result = processRunner.run(
            ProcessSpec(
                command = "git",
                args = args.toList(),
                workDir = workDir,
            )
        )
        return if (result.isSuccess) {
            Result.success(result.stdout)
        } else {
            Result.failure(
                GitCommandException(
                    command = "git ${args.joinToString(" ")}",
                    exitCode = result.exitCode,
                    stderr = result.stderr,
                )
            )
        }
    }
}

class GitCommandException(
    val command: String,
    val exitCode: Int,
    val stderr: String,
) : RuntimeException("git command failed (exit $exitCode): $command\n$stderr")

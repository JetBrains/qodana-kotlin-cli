package org.jetbrains.qodana.core.port

import org.jetbrains.qodana.core.model.QodanaError
import java.nio.file.Path

interface GitClient {
    suspend fun revParse(workDir: Path, ref: String): Result<String>
    suspend fun checkout(workDir: Path, ref: String): Result<Unit>
    suspend fun diff(workDir: Path, startRef: String?, endRef: String?): Result<String>
    suspend fun log(workDir: Path, format: String, maxCount: Int? = null): Result<String>
    suspend fun branch(workDir: Path): Result<String>
    suspend fun remoteUrl(workDir: Path): Result<String>
    suspend fun reset(workDir: Path, ref: String, hard: Boolean = false): Result<Unit>
    suspend fun fetch(workDir: Path, remote: String? = null, ref: String? = null, depth: Int? = null): Result<Unit>
    suspend fun isGitRepo(workDir: Path): Boolean
    suspend fun currentBranch(workDir: Path): Result<String>
    suspend fun currentRevision(workDir: Path): Result<String>
}

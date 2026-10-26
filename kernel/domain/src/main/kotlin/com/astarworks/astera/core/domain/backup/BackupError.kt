package com.astarworks.astera.core.domain.backup

/**
 * Sealed error type for backup / restore operations (Wave 5w / ADR-0089).
 *
 * Includes failure modes specific to a CLI-backed adapter (restic binary
 * missing, repository unreachable). Adapters wrap subprocess `IOException`s
 * into [SubprocessFailure] so application/ stays Throwable-free.
 */
public sealed interface BackupError {
    /** Restic CLI binary not on `$PATH` or unreadable. */
    public data class ResticBinaryUnavailable(val pathChecked: String) : BackupError

    /** Restic repository (S3 bucket / local path) couldn't be reached. */
    public data class RepositoryUnreachable(val repoUri: String, val cause: String) : BackupError

    /** Target was rejected by the policy (e.g. empty include set). */
    public data class InvalidTarget(val reason: String) : BackupError

    /** Snapshot id not found in the repository. */
    public data class SnapshotNotFound(val snapshotId: String) : BackupError

    /** Subprocess exited non-zero. */
    public data class SubprocessFailure(val exitCode: Int, val stderr: String) : BackupError

    /** Permission denied at the OS / S3 ACL level. */
    public data class PermissionDenied(val reason: String) : BackupError
}

package com.astarworks.astera.core.domain.backup

import java.time.Instant

/**
 * What the restore actually did (Wave 5w / ADR-0089).
 *
 * `filesRestored` is a metric, not a guarantee — the restic backend reports
 * it after applying the snapshot. `bytesRestored` is similarly informational.
 */
public data class RestoreReport(
    val snapshot: BackupSnapshotRef,
    val startedAt: Instant,
    val completedAt: Instant,
    val filesRestored: Long,
    val bytesRestored: Long,
    val dryRun: Boolean,
) {
    init {
        require(filesRestored >= 0) { "filesRestored must be non-negative; got $filesRestored" }
        require(bytesRestored >= 0) { "bytesRestored must be non-negative; got $bytesRestored" }
    }
}

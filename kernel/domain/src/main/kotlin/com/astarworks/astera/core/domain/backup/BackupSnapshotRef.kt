package com.astarworks.astera.core.domain.backup

import java.time.Instant

/**
 * Restic-side snapshot identifier (Wave 5w / ADR-0089).
 *
 * `id` corresponds to the restic short-id (8-char hex). `target` records
 * which [BackupTarget] this snapshot covers so listSnapshots can filter.
 */
public data class BackupSnapshotRef(
    val id: String,
    val target: BackupTarget,
    val takenAt: Instant,
    val sizeBytes: Long,
    val hostname: String,
) {
    init {
        require(id.isNotBlank()) { "BackupSnapshotRef.id must not be blank" }
        require(sizeBytes >= 0) { "BackupSnapshotRef.sizeBytes must be non-negative; got $sizeBytes" }
    }
}

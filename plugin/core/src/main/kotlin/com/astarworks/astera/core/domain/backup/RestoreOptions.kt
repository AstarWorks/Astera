package com.astarworks.astera.core.domain.backup

/**
 * Caller-supplied tuning for restic restore (Wave 5w / ADR-0089).
 *
 * `dryRun = true` exercises the restore code path without writing files;
 * `targetOverride` lets the operator restore a Postgres snapshot to a
 * different scratch directory (useful for partial-restore investigation).
 */
public data class RestoreOptions(
    val dryRun: Boolean = false,
    val targetOverride: BackupTarget? = null,
)

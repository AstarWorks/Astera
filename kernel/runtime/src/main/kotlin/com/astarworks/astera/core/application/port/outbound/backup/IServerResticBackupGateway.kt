package com.astarworks.astera.core.application.port.outbound.backup

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.BackupError
import com.astarworks.astera.core.domain.backup.BackupSnapshotRef
import com.astarworks.astera.core.domain.backup.BackupTarget
import com.astarworks.astera.core.domain.backup.RestoreOptions
import com.astarworks.astera.core.domain.backup.RestoreReport

/**
 * Outbound port (Wave 5w / ADR-0089): server-wide snapshot / restore via
 * restic (or compatible).
 *
 * **Server-scoped**, not player-scoped. Per-player JSON export / GDPR-style
 * data portability is a separate concern owned by Wave 5t's
 * `IPlayerDataExportGateway`.
 *
 * Phase 1 PoC ships `InMemoryServerResticBackupGateway` (priority 0 in-memory
 * stub) and a `/astera backup` Brigadier command tree. Wave 6b lands the
 * `ResticBackupGateway` (Paper adapter, subprocess over restic CLI, S3
 * endpoint config) at priority 100. ADR-0089 §β documents the carve-out.
 *
 * Konsist enforces (Wave 5w +1 rule): the production restic adapter MUST
 * isolate `java.lang.ProcessBuilder` / `java.lang.Process` invocations to
 * `*.adapter.paper.backup.*`. Application/ stays subprocess-free.
 */
public interface IServerResticBackupGateway {

    /** Take a fresh snapshot of [target]. */
    public fun runFullBackup(target: BackupTarget): Result<BackupSnapshotRef, BackupError>

    /**
     * List every snapshot whose [BackupSnapshotRef.target] matches [target].
     * `BackupTarget.All(...)` returns snapshots whose target is **any** of
     * the nested members.
     */
    public fun listSnapshots(target: BackupTarget): Collection<BackupSnapshotRef>

    /**
     * Restore a previously-taken snapshot. [options] gates `dryRun` and lets
     * the operator redirect the restore to a scratch directory.
     */
    public fun restoreFromSnapshot(
        snapshotRef: BackupSnapshotRef,
        options: RestoreOptions = RestoreOptions(),
    ): Result<RestoreReport, BackupError>
}

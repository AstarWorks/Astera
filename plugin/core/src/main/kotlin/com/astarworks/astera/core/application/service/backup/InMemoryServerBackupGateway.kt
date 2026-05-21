package com.astarworks.astera.core.application.service.backup

import com.astarworks.astera.core.application.port.outbound.backup.IServerBackupGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.BackupError
import com.astarworks.astera.core.domain.backup.BackupSnapshotRef
import com.astarworks.astera.core.domain.backup.BackupTarget
import com.astarworks.astera.core.domain.backup.RestoreOptions
import com.astarworks.astera.core.domain.backup.RestoreReport
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory stand-in for the restic-backed [IServerBackupGateway]
 * (Wave 5w / ADR-0089).
 *
 * Records every "snapshot" and "restore" so the `/astera backup` Brigadier
 * command works against a dev server without restic on `$PATH`. Wave 6b
 * lands `ResticBackupGateway` at priority 100, overriding this default.
 *
 * **All state is in-memory and lost on plugin disable** — that is by design;
 * production runs always have the priority-100 restic overlay active.
 */
public class InMemoryServerBackupGateway(
    private val hostname: String = "in-memory",
    private val clock: () -> Instant = { Instant.now() },
) : IServerBackupGateway {

    private val snapshots: MutableList<BackupSnapshotRef> = mutableListOf()
    private val idCounter = AtomicInteger(0)

    override fun runFullBackup(target: BackupTarget): Result<BackupSnapshotRef, BackupError> {
        if (target is BackupTarget.All && target.include.isEmpty()) {
            // Defensive: the data class blocks empty include sets in `init`, but
            // re-assert here to make the failure mode explicit at the port boundary.
            return Result.Failure(BackupError.InvalidTarget("All() requires non-empty include set"))
        }
        val ref = BackupSnapshotRef(
            id = makeId(),
            target = target,
            takenAt = clock(),
            sizeBytes = 0L,
            hostname = hostname,
        )
        snapshots += ref
        return Result.Success(ref)
    }

    override fun listSnapshots(target: BackupTarget): Collection<BackupSnapshotRef> {
        return when (target) {
            is BackupTarget.All -> {
                val accept = target.include
                snapshots.filter { it.target in accept }
            }
            else -> snapshots.filter { it.target == target }
        }
    }

    override fun restoreFromSnapshot(
        snapshotRef: BackupSnapshotRef,
        options: RestoreOptions,
    ): Result<RestoreReport, BackupError> {
        val found = snapshots.firstOrNull { it.id == snapshotRef.id }
            ?: return Result.Failure(BackupError.SnapshotNotFound(snapshotRef.id))
        val startedAt = clock()
        val completedAt = clock()
        return Result.Success(
            RestoreReport(
                snapshot = found,
                startedAt = startedAt,
                completedAt = completedAt,
                filesRestored = 0L,
                bytesRestored = 0L,
                dryRun = options.dryRun,
            ),
        )
    }

    /** Test-only hook: clears recorded snapshots between cases. */
    public fun reset() {
        snapshots.clear()
        idCounter.set(0)
    }

    private fun makeId(): String {
        val n = idCounter.incrementAndGet()
        return "inmem-%08x".format(n)
    }
}

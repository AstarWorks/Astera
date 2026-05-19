package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IStructurePaste
import com.astarworks.astera.core.application.port.outbound.PasteHandle
import com.astarworks.astera.core.application.port.outbound.StructurePasteError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic [IStructurePaste] for tests (Wave 5g, ADR-0072).
 *
 * Each [paste] call records the `(schematic, world, offset)` triple under a
 * freshly-minted [PasteHandle] derived from `UUID.randomUUID()`. [undo]
 * drops the record; per the port contract, [undo] is **idempotent** —
 * undoing an already-undone (or never-pasted) handle returns
 * [Result.Success], not an error.
 *
 * No real block I/O happens — the Paper-side FAWE adapter (Wave 6b) is the
 * only impl that touches actual world geometry. Tests inspect [pasted] to
 * verify the paste → undo round-trip without spinning up Bukkit.
 *
 * Thread-safe via [ConcurrentHashMap]; scenario harnesses that hand-roll
 * concurrent pastes (Wave 8+) can rely on this without surprises.
 */
public class FakeStructurePaste : IStructurePaste {

    /** Snapshot of one paste, surfaced for test assertions. */
    public data class PasteRecord(
        public val schematic: Schematic,
        public val world: WorldId,
        public val offset: Vec3,
    )

    private val records: ConcurrentHashMap<PasteHandle, PasteRecord> = ConcurrentHashMap()

    /** Read-only view of every still-live paste keyed by handle. */
    public val pasted: Map<PasteHandle, PasteRecord>
        get() = records.toMap()

    override fun paste(
        schematic: Schematic,
        world: WorldId,
        offset: Vec3,
    ): Result<PasteHandle, StructurePasteError> {
        val handle = PasteHandle(UUID.randomUUID().toString())
        records[handle] = PasteRecord(schematic, world, offset)
        return Result.success(handle)
    }

    override fun undo(handle: PasteHandle): Result<Unit, StructurePasteError> {
        // Per IStructurePaste.undo: idempotent — undoing an already-undone
        // (or unknown) handle is a no-op success.
        records.remove(handle)
        return Result.success(Unit)
    }
}

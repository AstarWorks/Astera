package com.astarworks.astera.core.application.service

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
 * Wave 6a-N3 (ADR-0159): production [IStructurePaste] impl — Phase 1 stub.
 *
 * Records each `(schematic, world, offset)` triple under a freshly-minted
 * [PasteHandle]; [undo] drops the record (per the port contract `undo` is
 * **idempotent** — undoing an unknown / already-undone handle returns
 * [Result.Success]).
 *
 * **No real block I/O.** The Paper-side FAWE adapter (Wave 6b+,
 * `PaperStructurePaste`) is the only impl that touches actual world geometry;
 * Phase 1 PoC scenarios that exercise paste/undo (anomaly-spawned structures
 * with wholesale undo on expiry) are satisfied by the in-memory delta
 * capture this class provides.
 *
 * Diverges from the test-fixture `FakeStructurePaste` only in package
 * location (this class lives in `application/service/` so it is registrable
 * in `AsteraCorePlugin.onEnable` at priority 0 without dragging test
 * fixtures into the production classpath). The Wave 6b FAWE adapter will
 * overlay at priority 100 when shipped, transparently replacing this stub.
 *
 * Naming convention divergence: ADR-0072 reserved Wave 6b "PaperStructurePaste"
 * naming under `adapter/paper/`, but the **contract is pure Kotlin** until
 * FAWE wires real I/O — same posture as
 * [com.astarworks.astera.match.application.service.MatchTimerService]
 * (Wave 6a-N1 / ADR-0154). The IStructurePaste entry leaves the
 * ADR-0072 allowedMissingPaperImpl set with a KDoc comment pointing here.
 *
 * Thread-safe via [ConcurrentHashMap].
 */
public class InMemoryStructurePaste : IStructurePaste {

    private val records: ConcurrentHashMap<PasteHandle, Triple<Schematic, WorldId, Vec3>> =
        ConcurrentHashMap()

    override fun paste(
        schematic: Schematic,
        world: WorldId,
        offset: Vec3,
    ): Result<PasteHandle, StructurePasteError> {
        val handle = PasteHandle(UUID.randomUUID().toString())
        records[handle] = Triple(schematic, world, offset)
        return Result.success(handle)
    }

    override fun undo(handle: PasteHandle): Result<Unit, StructurePasteError> {
        records.remove(handle)
        return Result.success(Unit)
    }
}

package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: paste a [Schematic] into a live world *and remember it* so
 * the paste can be undone wholesale (e.g. when the anomaly that spawned the
 * structure expires).
 *
 * Wave 5g (Core hardening) splits responsibilities:
 * - [ISchematicLoader] = file I/O (load + initial place). Match-start
 *   geometry. Pastes are not tracked.
 * - [IStructurePaste] (this port) = runtime, in-match, ephemeral pastes
 *   that need wholesale undo. Pastes are tracked via [PasteHandle].
 *
 * Per ADR-0072: this is the port shape Wave 6b
 * (`PaperStructurePaste` over FAWE) will implement. Wave 5g ships
 * `FakeStructurePaste` which records the block deltas in memory so unit /
 * scenario tests can verify the paste → undo round-trip.
 *
 * Maps onto RTM's `StructureService` design.
 */
public interface IStructurePaste {

    /**
     * Paste [schematic] into [world] with its origin at [offset], returning a
     * handle the caller stores for later [undo].
     *
     * Implementations must capture the block deltas (overwritten block
     * states) so [undo] is a true inverse.
     */
    public fun paste(
        schematic: Schematic,
        world: WorldId,
        offset: Vec3,
    ): Result<PasteHandle, StructurePasteError>

    /**
     * Undo a previously-issued [paste] — restore the captured block deltas.
     *
     * Idempotent: undoing an already-undone handle is a no-op success.
     */
    public fun undo(handle: PasteHandle): Result<Unit, StructurePasteError>
}

/**
 * Opaque handle returned by [IStructurePaste.paste]. Stable identity for the
 * lifetime of the impl; not portable across plugin restarts (anomaly pastes
 * are ephemeral by design).
 */
@JvmInline
public value class PasteHandle(public val value: String) {
    init {
        require(value.isNotBlank()) { "PasteHandle must not be blank" }
    }

    override fun toString(): String = value
}

public sealed class StructurePasteError {
    public data class UnknownWorld(public val world: WorldId) : StructurePasteError()
    public data class UnknownHandle(public val handle: PasteHandle) : StructurePasteError()
    public data class WriteFailed(public val detail: String) : StructurePasteError()
}

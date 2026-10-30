package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.StatePath

/** Observes a cast's control flow (state enter / exit, fired events, sub-table calls) for tests and tooling; never affects play. */
public interface CastTrace {
    public fun enter(cast: CastHandle, state: StatePath, tick: Int)

    public fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int)

    public fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?)

    public fun call(cast: CastHandle, callId: InstanceId, tick: Int)

    /** A row of op instance [op] starts playing (the particles, sounds and visual writes it issues belong to that op). */
    public fun row(cast: CastHandle, op: InstanceId, tick: Int) {}

    /** A chain hop's pick row ran (ADR-0303 9.4): [victim] is the entity it chose, or null when nobody qualified (that hop and every later hop of the chain are skipped). */
    public fun pick(cast: CastHandle, op: InstanceId, tick: Int, victim: java.util.UUID?) {}

    /** The resolved volume [op] is about to run its broad phase with (a hit op, or a projectile body sweeping). */
    public fun hitVolume(cast: CastHandle, op: InstanceId, volume: ResolvedVolume, tick: Int) {}

    /** A live `frame` instance's world pose at the end of [tick] (frames are internal to the player, so the oracle sees them only here). */
    public fun frame(cast: CastHandle, op: InstanceId, tick: Int, position: Vec3, rotation: Quaternion) {}

    /** A `react player:` clip row did not play because [victim] is not a player (a mob plays the flinch / push / hold of the same `react` and no clip). */
    public fun reactClipSkipped(cast: CastHandle, op: InstanceId, tick: Int, victim: java.util.UUID?) {}

    /** A `status` op did not apply to [victim] because this cast's damage already killed it. */
    public fun statusSkippedDead(cast: CastHandle, op: InstanceId, tick: Int, victim: java.util.UUID?) {}

    /** The cast ended at [tick] but its handlers still hold [rows] gameplay rows (rule D, rev4.14); the last plays at [until]. The tail starts after it. */
    public fun drain(cast: CastHandle, tick: Int, rows: Int, until: Int) {}

    /** A non-detached presentation row [row] (its YAML path) of a handler was dropped because its owner [state] exited ([reason]) before the row's tick. */
    public fun cut(cast: CastHandle, row: String, tick: Int, state: StatePath, reason: ExitReason) {}

    /** An event reached sub-table [sub] and was dropped, not pasted: [reason] says why (`owner-exited:<state>` or `outside-window:lt=<n>`). Never silent. */
    public fun dropped(cast: CastHandle, sub: EventPath, tick: Int, reason: String) {}

    public companion object {
        public val NONE: CastTrace = object : CastTrace {
            override fun enter(cast: CastHandle, state: StatePath, tick: Int) {}
            override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) {}
            override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) {}
            override fun call(cast: CastHandle, callId: InstanceId, tick: Int) {}
        }
    }
}

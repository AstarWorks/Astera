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

    public companion object {
        public val NONE: CastTrace = object : CastTrace {
            override fun enter(cast: CastHandle, state: StatePath, tick: Int) {}
            override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) {}
            override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) {}
            override fun call(cast: CastHandle, callId: InstanceId, tick: Int) {}
        }
    }
}

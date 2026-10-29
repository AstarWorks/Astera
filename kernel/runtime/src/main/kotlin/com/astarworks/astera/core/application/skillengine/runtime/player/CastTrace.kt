package com.astarworks.astera.core.application.skillengine.runtime.player

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

    /** The resolved volume [op] is about to run its broad phase with (a hit op, or a projectile body sweeping). */
    public fun hitVolume(cast: CastHandle, op: InstanceId, volume: ResolvedVolume, tick: Int) {}

    public companion object {
        public val NONE: CastTrace = object : CastTrace {
            override fun enter(cast: CastHandle, state: StatePath, tick: Int) {}
            override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) {}
            override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) {}
            override fun call(cast: CastHandle, callId: InstanceId, tick: Int) {}
        }
    }
}

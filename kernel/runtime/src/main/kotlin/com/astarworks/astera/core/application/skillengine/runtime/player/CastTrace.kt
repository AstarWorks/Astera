package com.astarworks.astera.core.application.skillengine.runtime.player

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

    public companion object {
        public val NONE: CastTrace = object : CastTrace {
            override fun enter(cast: CastHandle, state: StatePath, tick: Int) {}
            override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) {}
            override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) {}
            override fun call(cast: CastHandle, callId: InstanceId, tick: Int) {}
        }
    }
}

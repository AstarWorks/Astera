package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.application.skillengine.runtime.CastError
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EntityRef
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import java.util.UUID

@JvmInline
public value class CastHandle(public val value: Long)

/** Opaque id of a thing a gateway spawned (display, trail, body). */
@JvmInline
public value class GatewayHandle(public val id: Long)

public data class ContactInfo(val point: Vector3d, val normal: Vector3d)

/** What a delivered event binds for the sub-table pasted by it: `event.i`, `contact`, `target`. */
public data class EventBinding(
    val event: EventPath,
    val index: Int?,
    val contact: ContactInfo?,
    val victim: EntityRef?,
    /** `event.attacker`: who dealt the damage of a `hurt` event; null when the event has no attacker. */
    val attacker: UUID? = null,
    /** `from` (chain targeting, ADR-0303 9.4): the previous victim of the chain, bound while a chain hop's rows run; null outside a chain. */
    val from: EntityRef? = null,
)

public data class RuntimeEvent(
    val path: EventPath,
    val tick: Int,
    val index: Int?,
    val contact: ContactInfo?,
    val victim: EntityRef?,
    val attacker: UUID? = null,
)

/** Plays the expansion table (ADR-0303 C7). */
public interface ISkillTablePlayer {
    public fun start(request: CastRequest, skill: CompiledSkill): Result<CastHandle, CastError>

    /** Plays table[state][now - entry] for every live cast, in cast order, rows in table order. */
    public fun tick(now: Int)

    /** Queues; consumed at the next tick in the 10.2 order (input, gameplay, timers, guards). */
    public fun deliver(cast: CastHandle, event: RuntimeEvent)

    /**
     * F2: would delivering [event] now fire an `absorb: true` branch (first matching branch of the innermost level, guard true)? Pure
     * query, nothing is consumed: the platform asks it inside the damage pipeline, where the queued `hurt` event comes too late to cancel.
     */
    public fun absorbs(cast: CastHandle, event: RuntimeEvent, now: Int): Boolean

    public fun cancel(cast: CastHandle, scope: CancelScope)

    public fun live(): List<CastHandle>
}

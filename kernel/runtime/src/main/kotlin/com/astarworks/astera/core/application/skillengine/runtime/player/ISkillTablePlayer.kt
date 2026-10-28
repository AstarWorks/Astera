package com.astarworks.astera.core.application.skillengine.runtime.player

import com.astarworks.astera.core.application.skillengine.runtime.CastError
import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.EntityRef
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import org.joml.Vector3d
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

    public fun cancel(cast: CastHandle, scope: CancelScope)

    public fun live(): List<CastHandle>
}

package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.ContactEvent
import com.astarworks.astera.core.domain.skillengine.modifier.ModifierStack
import java.util.UUID

@JvmInline
public value class CastId(public val value: Long)

/** Everything fixed at cast start (modifiers are snapshotted here, F-12). */
public data class CastRequest(
    val skill: CompiledSkill,
    val caster: UUID,
    val casterFeet: Vec3,
    val casterYawDeg: Double,
    val casterPitchDeg: Double,
    val seed: Long,
    val modifiers: ModifierStack = ModifierStack.EMPTY,
    val startTick: Int,
)

public sealed interface CastError {
    public data class FrameSetup(val reason: String) : CastError
    public data class ExprFailed(val path: String, val reason: String) : CastError
}

/**
 * One event on a cast's private bus. [contact] is set for hit/block. [victim] is the entity that was the current victim when a
 * gameplay op raised or scheduled the event: its `on:` handlers run in that victim's binding (a presentation op written for a
 * dash `on_hit` or a window body still addresses the entity it hit).
 */
public data class CastEvent(
    val name: String,
    val tick: Int,
    val source: String? = null,
    val contact: ContactEvent? = null,
    val victim: java.util.UUID? = null,
)

/** Per-cast event bus: gameplay raises, both sections' `on:` handlers (and `until:` clauses) consume. */
public interface CastEventBus {
    public fun raise(event: CastEvent)
    public fun subscribe(listener: (CastEvent) -> Unit): AutoCloseable
    public fun hasRaised(name: String): Boolean
}

/**
 * Runs casts. One implementation drives the timeline, per-tick motion laws, hit volumes,
 * handlers, and presentation, in that order (gameplay then presentation, design 3.7).
 */
public interface CastRunner {
    public fun start(request: CastRequest): Result<CastId, CastError>

    /** Raise an external event on a running cast (e.g. `release` from an input). */
    public fun raise(cast: CastId, event: CastEvent)

    public fun cancel(cast: CastId)

    public fun isRunning(cast: CastId): Boolean

    /** Cancels every running cast of [caster] (quit, death, weapon swap, world change); returns how many ended. */
    public fun cancelAllOf(caster: java.util.UUID): Int

    /** Cancels every running cast (plugin disable, reload). */
    public fun cancelAll(): Int

    /** Advance every running cast by one server tick. Called once per tick by the platform. */
    public fun tick(currentTick: Int)
}

package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.load.CompiledSkill
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.HitOp

/**
 * A presentation object that follows frames every tick (mesh, plate, line, trail, repeating sound).
 * Registered by a presentation executor under a key (usually the op id); `remove` ops and the end of
 * the cast call [dispose]. [update] runs after all handlers of the tick; returning false unregisters it
 * (and [dispose] is called).
 */
public interface PresentationUpdater {
    public fun update(ctx: CastOpContext): Boolean
    public fun dispose(ctx: CastOpContext)
}

/**
 * [OpContext] plus the hooks the cast instance owns: motion laws, hit volumes, scheduled events,
 * freeze and per-tick presentation updaters. Executors receive this object as their `OpContext` and
 * downcast (`ctx as CastOpContext`) when they need these hooks; the interface in `OpExecutor.kt`
 * stays the contract. The runner implements no op semantics, executors call these.
 */
public interface CastOpContext : OpContext {
    public val request: CastRequest
    public val skill: CompiledSkill

    /** Absolute server tick being processed. */
    public val tick: Int
    public val ticksSinceStart: Int

    /** Id of the op being executed (the `event.source` of events it raises). */
    public val currentOpId: String?

    /** The event whose handler list is running (null inside timeline rows). */
    public val event: CastEvent?
    public val env: CastEnv

    public fun hasRaised(name: String): Boolean

    /** World pose of a frame, null when unknown. */
    public fun frameWorld(frame: String): Frame?

    /**
     * Start a motion law on [frame]. Starting `Stick` or `Reel` replaces every law on that frame; `Fly`,
     * `Path` and `Follow` replace the frame's other translate laws; `Pose` and `Spin` coexist.
     */
    public fun startMotion(frame: String, spec: MotionSpec)

    /** Stop every law on [frame] (no event). */
    public fun stopMotion(frame: String)

    /** Register [op] on each of its frames; contacts raise `hit` (or `block`) with `event.source == op.id`. */
    public fun registerHit(op: HitOp.Hit)

    public fun unregisterHit(opId: String)

    /** Raise [event] after [afterTicks] ticks (source = the scheduling op). */
    public fun schedule(afterTicks: Int, event: String)

    /** Stop motion laws and hit volumes of [frames] and their descendants for [ticks] ticks (hit-stop). */
    public fun freeze(frames: List<String>, ticks: Int)

    public fun registerUpdater(key: String, updater: PresentationUpdater)

    /** Dispose and drop the updater registered under [key]; false when none. */
    public fun removeUpdater(key: String): Boolean

    /** End the cast after the current tick (raises `end`). */
    public fun finish()
}

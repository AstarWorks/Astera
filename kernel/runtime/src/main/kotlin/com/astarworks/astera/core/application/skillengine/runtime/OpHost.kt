package com.astarworks.astera.core.application.skillengine.runtime

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillCooldownGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import java.util.UUID

/** One hit volume instance on one [frame]; its contacts carry `event.source == id`. */
public data class HitRegistration(
    val id: String,
    val frame: String,
    val volume: HitVolume,
    val window: HitWindow,
    val startTick: Int,
    /** Lifetime cap of accepted victims, nearest first; null = unlimited. */
    val maxCount: Int? = null,
    /** See [com.astarworks.astera.core.domain.skillengine.op.HitOp.Hit.swept]. */
    val swept: Boolean = false,
)

/**
 * Cast-scoped services behind the frame / hit / control executors (W10). Implemented once per cast
 * by the cast runner; executors never see the runner itself.
 */
public interface OpHost {
    public val caster: UUID

    /** Entity of the contact being handled (hit/block handlers), or the for_each binding. */
    public val currentVictim: UUID?
    public val tick: Int

    /** World-space normal of the current contact, if a hit/block is being handled. */
    public val contactNormal: Vec3?

    /** The caster's look direction including pitch. */
    public fun lookDirection(): Vec3

    public fun registerHit(registration: HitRegistration)

    /** Start a motion law on [frame] (the runner owns the factory and the per-tick stepping). */
    public fun startMotion(frame: FrameId, spec: MotionSpec)

    /** Raise [event] (carrying `event.source == source`) after [afterTicks]. */
    public fun schedule(afterTicks: Int, event: String, source: String)

    /** Run [ops] again in the current scope (same victim binding) after [afterTicks]. */
    public fun scheduleOps(afterTicks: Int, ops: List<SkillOp>)

    /** Hit-stop on the frame subtrees named in [frames]. */
    public fun freezeFrames(frames: List<String>, ticks: Int)

    /** Start another skill as a child cast. The runner enforces chain depth and cycles. */
    public fun castSkill(skillId: String)

    /** Open a [op] window on the caster for [ticks] ticks (see [com.astarworks.astera.core.domain.skillengine.op.ControlOp.Window]). */
    public fun openWindow(op: com.astarworks.astera.core.domain.skillengine.op.ControlOp.Window, ticks: Int)

    public fun addCounter(key: String, delta: Double)

    /** Deterministic per-cast random in [0, 1) (seeded from the cast request). */
    public fun nextRandom(): Double

    public fun resolveTargets(ref: TargetRef): List<UUID>

    /** Run [block] with [victim] bound as the current target. */
    public fun withVictim(victim: UUID, block: () -> Unit)

    /** Report an op that could not run (bad frame, cycle); the runner logs it with the skill id. */
    public fun fail(opId: String, reason: String)

    /** [fail] for the op being executed right now (a referent that does not resolve is a loud error, never a skipped op). */
    public fun failHere(reason: String): Unit = fail("?", reason)

    public val cooldowns: ISkillCooldownGateway
    public val items: ISkillItemGateway
}

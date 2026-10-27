package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.HitWindow
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.motion.MotionSpec

/**
 * One handler / timeline entry. Every op has a unique `id` within its skill (so patches can target
 * it) and an optional `when: "= <expr>"` guard.
 */
public sealed interface SkillOp {
    public val id: String
    public val whenExpr: Value<Boolean>?
}

/** Ops allowed in the `gameplay` section: they move frames, decide hits and apply effects. */
public sealed interface GameplayOp : SkillOp {

    /** Start a motion law on [frame]: pose / spin / fly / path / follow / stick / reel. */
    public data class Motion(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frame: String,
        val spec: MotionSpec,
    ) : GameplayOp

    /** Re-parent [frames] to [to] (keep world pose, or snap). */
    public data class Reparent(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frames: List<String>,
        val to: String,
        val keepWorld: Boolean,
        val snap: SnapSpec?,
    ) : GameplayOp

    /** Register a hit volume on [frames]; its contacts carry `event.source == id`. */
    public data class Hit(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val frames: List<String>,
        val volume: HitVolume,
        val window: HitWindow,
    ) : GameplayOp

    public data class Damage(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val components: List<DamageComponentSpec>,
    ) : GameplayOp

    public data class ApplyStatus(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val statusId: String,
        val stacks: Value<Double>,
        val durationTicks: Value<Double>,
        val chance: Value<Double>?,
    ) : GameplayOp

    public data class Impulse(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val vector: Value<Vec3>,
    ) : GameplayOp

    /** Stop the clock of the listed frame subtrees (hit-stop). */
    public data class Freeze(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val ticks: Value<Double>,
        val scope: List<String>,
    ) : GameplayOp

    /** Raise [event] after a delay. */
    public data class Schedule(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val afterTicks: Value<Double>,
        val event: String,
    ) : GameplayOp

    /** Raise [event] now. */
    public data class RaiseEvent(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val event: String,
    ) : GameplayOp
}

public data class DamageComponentSpec(val kind: String, val amount: Value<Double>)

/** Re-parent snap: put [point] of the frame on the new parent's origin, sunk [depth] along the contact normal. */
public data class SnapSpec(val point: PointRef?, val depth: Value<Double>?)

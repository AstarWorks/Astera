package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Entity movement (not frame movement). Family owner: movement worker. Directions are world vectors. */
public sealed interface MovementOp : GameplayOp {

    /** Add [vector] (blocks/tick) to the entity velocity. */
    public data class Impulse(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val vector: Value<Vec3>,
    ) : MovementOp

    public data class Knockback(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val direction: Value<Vec3>,
        val strength: Value<Double>,
    ) : MovementOp

    public data class Knockup(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val strength: Value<Double>,
    ) : MovementOp

    public data class SetVelocity(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val direction: Value<Vec3>,
        val magnitude: Value<Double>,
    ) : MovementOp

    public data class Propel(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val direction: Value<Vec3>,
        val strength: Value<Double>,
    ) : MovementOp

    public data class Teleport(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val destination: LocationSpec,
    ) : MovementOp

    public data class Leap(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val destination: LocationSpec,
        val arcHeight: Value<Double>,
    ) : MovementOp

    /** Caster dash that hits what it touches; [onHit] runs per contact (contact event in scope). */
    public data class DashStrike(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val distance: Value<Double>,
        val ticks: Value<Double>,
        val radius: Value<Double>,
        val hitOnce: Boolean,
        val invulnerable: Boolean,
        val stopOnBlock: Boolean,
        val followPitch: Boolean,
        val stopBeforeTarget: Double?,
        val localDirection: Vec3?,
        val faceMovement: Boolean,
        val onHit: List<GameplayOp>,
    ) : MovementOp

    /**
     * Movement lock (windup / cast phases): [speed] is the walk-speed factor kept while locked (0 = full root,
     * 0.3 = slowed to 30%; the view stays free). Held [ticks] ticks, or until `unlock` /
     * cast end / cancel when [ticks] is null. Several locks on one entity apply the strictest.
     */
    public data class Lock(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val speed: Value<Double>,
        val ticks: Value<Double>?,
    ) : MovementOp

    /** Releases the locks this cast placed on [target] early. */
    public data class Unlock(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
    ) : MovementOp
}

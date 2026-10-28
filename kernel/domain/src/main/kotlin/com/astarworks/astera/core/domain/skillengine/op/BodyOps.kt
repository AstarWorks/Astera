package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Body animation of caster / victims. Family owner: body-anim worker. */
public sealed interface BodyOp : PresentationOp {

    public data class VictimAnim(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val anim: String,
        val strength: Value<Double>?,
    ) : BodyOp

    public data class CasterAnim(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val anim: String,
        val speed: Value<Double>?,
        /** Hold time for pose-only clips (`crouch`, `hide-main-hand`); a `content/animations` clip runs its own length. */
        val ticks: Int?,
        /** Event that stops the clip early (blends out); a cancelled cast stops it too. */
        val untilEvent: String?,
    ) : BodyOp
}

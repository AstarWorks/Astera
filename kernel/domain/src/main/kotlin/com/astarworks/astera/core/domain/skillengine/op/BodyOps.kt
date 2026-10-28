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
    ) : BodyOp {
        public companion object {
            /**
             * The victim reactions the engine can play. v1 had no `victim_anim` op; its victim-side presentation was the vanilla
             * hurt flash (`hurt`), the impact freeze (`hit_stop`, now the `hit_stop` op) and a pushed stagger (`stagger`,
             * `stagger_back`: the victim recoils away from the contact point).
             */
            public val KNOWN: Set<String> = setOf("hurt", "stagger", "stagger_back")
        }
    }

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

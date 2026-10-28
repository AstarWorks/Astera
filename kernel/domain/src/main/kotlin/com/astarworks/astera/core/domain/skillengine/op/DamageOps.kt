package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Direct damage / healing / explosions. Family owner: damage worker (projectile/aoe lane). */
public sealed interface DamageOp : GameplayOp {

    public data class Damage(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val components: List<DamageComponentSpec>,
        /** Bypass the victim's invulnerability frames (v1 `ignore_invulnerability`). */
        val ignoreInvulnerability: Boolean = false,
        /** Skip the victim's armor / resist reduction for this hit (v1 `ignore_armor`). */
        val ignoreArmor: Boolean = false,
    ) : DamageOp

    public data class Heal(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val amount: Value<Double>,
    ) : DamageOp

    public data class Resurrect(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val healthFraction: Value<Double>,
    ) : DamageOp

    public data class Explode(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val power: Value<Double>,
        val breakBlocks: Boolean,
        val incendiary: Boolean,
        val radius: Value<Double>?,
        val components: List<DamageComponentSpec>,
        /** Blast centre; null = the position of [target]. */
        val at: LocationSpec? = null,
    ) : DamageOp
}

public data class DamageComponentSpec(
    val kind: String,
    val amount: Value<Double>,
    val perStack: PerStackSpec? = null,
)

/**
 * Adds `amount x stacks` of [status] (read on [on] before the hit) to a damage component. [consume] removes the
 * stacks once the whole op has landed.
 */
public data class PerStackSpec(
    val status: String,
    val amount: Value<Double>,
    val on: StackSide,
    val consume: Boolean,
)

public enum class StackSide { CASTER, VICTIM }

package com.astarworks.astera.core.domain.skillengine.op

import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Direct damage / healing / explosions. Family owner: damage worker (projectile/aoe lane). */
public sealed interface DamageOp : GameplayOp {

    public data class Damage(
        override val id: String,
        override val whenExpr: Value<Boolean>?,
        val target: TargetRef,
        val components: List<DamageComponentSpec>,
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
    ) : DamageOp
}

public data class DamageComponentSpec(val kind: String, val amount: Value<Double>)

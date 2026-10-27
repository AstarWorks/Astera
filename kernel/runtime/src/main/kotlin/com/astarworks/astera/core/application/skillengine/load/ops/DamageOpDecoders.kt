package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.op.DamageComponentSpec
import com.astarworks.astera.core.domain.skillengine.op.DamageOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

public fun damageDecoders(): List<OpDecoder> = listOf(DamageOpDecoder)

/**
 * `damage`: target?, components: [{kind, amount}]. `heal`: target?, amount. `resurrect`: target?,
 * health_fraction?. `explode`: target?, power, break_blocks?, incendiary?, radius?, components?.
 * Default target is `victim` (the contact in scope) for damage and `caster` for heal / resurrect.
 */
internal object DamageOpDecoder : OpDecoder {
    override val section: OpSection = OpSection.GAMEPLAY
    override val opNames: Set<String> = setOf("damage", "heal", "resurrect", "explode")

    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = when (op) {
        "damage" -> DamageOp.Damage(
            id = id,
            whenExpr = whenExpr,
            target = reader.target("target", TargetRef.Victim),
            components = components(reader, required = true),
        )
        "heal" -> DamageOp.Heal(
            id = id,
            whenExpr = whenExpr,
            target = reader.target("target", TargetRef.Caster),
            amount = reader.requireNumber("amount"),
        )
        "resurrect" -> DamageOp.Resurrect(
            id = id,
            whenExpr = whenExpr,
            target = reader.target("target", TargetRef.Caster),
            healthFraction = reader.number("health_fraction") ?: literal(1.0),
        )
        "explode" -> DamageOp.Explode(
            id = id,
            whenExpr = whenExpr,
            target = reader.target("target", TargetRef.Victim),
            power = reader.requireNumber("power"),
            breakBlocks = reader.bool("break_blocks") ?: false,
            incendiary = reader.bool("incendiary") ?: false,
            radius = reader.number("radius"),
            components = components(reader, required = false),
        )
        else -> reader.fail("unknown damage op '$op'")
    }

    private fun components(reader: OpReader, required: Boolean): List<DamageComponentSpec> {
        val raw = reader.raw("components") as? List<*>
        if (raw == null) {
            if (required) reader.fail("'components' (list of {kind, amount}) is required")
            return emptyList()
        }
        return raw.map { entry ->
            val m = entry as? Map<*, *> ?: reader.fail("each component must be {kind, amount}")
            DamageComponentSpec(
                kind = m["kind"]?.toString() ?: reader.fail("component.kind missing"),
                amount = parseNumber(m["amount"]) ?: reader.fail("component.amount missing"),
            )
        }
    }
}

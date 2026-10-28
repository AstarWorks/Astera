package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.op.DamageComponentSpec
import com.astarworks.astera.core.domain.skillengine.op.DamageOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.PerStackSpec
import com.astarworks.astera.core.domain.skillengine.op.StackSide
import com.astarworks.astera.core.domain.skillengine.op.SkillOp

public fun damageDecoders(): List<OpDecoder> = listOf(DamageOpDecoder)

/**
 * `damage`: target?, components: [{kind, amount}]. `heal`: target?, amount. `resurrect`: target?,
 * health_fraction?. `explode`: target? or at?, power, break_blocks?, incendiary?, radius?, components?.
 * `damage` may set `ignore_invulnerability` and `ignore_armor`; a component may carry `per_stack: {status, amount, on: caster|victim,
 * consume}` (adds `amount x stacks`, read before the hit, removed after the op when `consume`).
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
            ignoreInvulnerability = reader.bool("ignore_invulnerability") ?: false,
            ignoreArmor = reader.bool("ignore_armor") ?: false,
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
            at = if (reader.raw("at") != null) reader.location("at") else null,
        )
        else -> reader.fail("unknown damage op '$op'")
    }

    private fun perStack(m: Map<*, *>, reader: OpReader): PerStackSpec = PerStackSpec(
        status = m.also { onlyKeys(it, setOf("status", "amount", "on", "consume"), reader, "per_stack") }["status"]?.toString() ?: reader.fail("per_stack.status missing"),
        amount = parseNumber(m["amount"]) ?: reader.fail("per_stack.amount missing"),
        on = when (val side = m["on"]?.toString() ?: "caster") {
            "caster" -> StackSide.CASTER
            "victim" -> StackSide.VICTIM
            else -> reader.fail("per_stack.on must be caster or victim, got '$side'")
        },
        consume = m["consume"] as? Boolean ?: false,
    )

    private fun onlyKeys(m: Map<*, *>, allowed: Set<String>, reader: OpReader, what: String) {
        m.keys.firstOrNull { it.toString() !in allowed }?.let { reader.fail("$what: unknown key '$it'") }
    }

    private fun components(reader: OpReader, required: Boolean): List<DamageComponentSpec> {
        val raw = reader.raw("components") as? List<*>
        if (raw == null) {
            if (required) reader.fail("'components' (list of {kind, amount}) is required")
            return emptyList()
        }
        return raw.map { entry ->
            val m = entry as? Map<*, *> ?: reader.fail("each component must be {kind, amount}")
            onlyKeys(m, setOf("kind", "amount", "per_stack"), reader, "component")
            DamageComponentSpec(
                kind = m["kind"]?.toString() ?: reader.fail("component.kind missing"),
                amount = parseNumber(m["amount"]) ?: reader.fail("component.amount missing"),
                perStack = (m["per_stack"] as? Map<*, *>)?.let { perStack(it, reader) },
            )
        }
    }
}

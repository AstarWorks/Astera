package com.astarworks.astera.core.application.skillengine.load.ops

import com.astarworks.astera.core.application.skillengine.load.OpDecoder
import com.astarworks.astera.core.application.skillengine.load.OpReader
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.model.literal
import com.astarworks.astera.core.domain.skillengine.op.BlockMode
import com.astarworks.astera.core.domain.skillengine.op.GameplayOp
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.SpawnOp

public fun spawnDecoders(): List<OpDecoder> = listOf(SpawnOpDecoder)

/**
 * `projectile`: body, from, velocity, gravity?, drag?, max_ticks?, pierce?, capsule|sphere|sector,
 * on_hit? / on_block? / on_expire? (op lists), block_mode? (despawn|stick|bounce|pass) with stick_ticks? / restitution? / max_bounces?. `aoe`: at, capsule|sphere|sector|radius, on_hit?.
 * `summon`: at, entity_type, count?, lifetime_ticks?. `summon_lightning`: at, visual_only?.
 */
internal object SpawnOpDecoder : OpDecoder {
    override val section: OpSection = OpSection.GAMEPLAY
    override val opNames: Set<String> = setOf("projectile", "aoe", "summon", "summon_lightning")

    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp = when (op) {
        "projectile" -> SpawnOp.Projectile(
            id = id,
            whenExpr = whenExpr,
            body = reader.requireString("body"),
            from = reader.location("from"),
            velocity = reader.vec3("velocity") ?: reader.fail("projectile needs 'velocity'"),
            gravity = reader.number("gravity") ?: literal(0.0),
            drag = reader.number("drag") ?: literal(0.0),
            maxTicks = reader.number("max_ticks") ?: literal(200.0),
            volume = reader.hitVolume(allowRadiusShorthand = true),
            pierce = reader.int("pierce") ?: 0,
            onHit = gameplayOps(reader, "on_hit"),
            onBlock = gameplayOps(reader, "on_block"),
            onExpire = gameplayOps(reader, "on_expire"),
            blockMode = blockMode(reader),
            stickTicks = reader.number("stick_ticks") ?: literal(60.0),
            restitution = reader.number("restitution") ?: literal(0.6),
            maxBounces = reader.int("max_bounces") ?: 3,
        )
        "aoe" -> SpawnOp.Aoe(
            id = id,
            whenExpr = whenExpr,
            at = reader.location("at"),
            volume = reader.hitVolume(allowRadiusShorthand = true),
            onHit = gameplayOps(reader, "on_hit"),
        )
        "summon" -> SpawnOp.Summon(
            id = id,
            whenExpr = whenExpr,
            at = reader.location("at"),
            entityType = reader.requireString("entity_type"),
            count = reader.number("count") ?: literal(1.0),
            lifetimeTicks = reader.number("lifetime_ticks") ?: literal(600.0),
        )
        "summon_lightning" -> SpawnOp.SummonLightning(
            id = id,
            whenExpr = whenExpr,
            at = reader.location("at"),
            visualOnly = reader.bool("visual_only") ?: false,
        )
        else -> reader.fail("unknown spawn op '$op'")
    }

    private fun blockMode(reader: OpReader): BlockMode = when (val m = reader.string("block_mode") ?: "despawn") {
        "despawn" -> BlockMode.Despawn
        "stick" -> BlockMode.Stick
        "bounce" -> BlockMode.Bounce
        "pass" -> BlockMode.Pass
        else -> reader.fail("block_mode '$m' is not one of despawn|stick|bounce|pass")
    }

    private fun gameplayOps(reader: OpReader, key: String): List<GameplayOp> =
        reader.ops(key).map { it as? GameplayOp ?: reader.fail("'$key' may only hold gameplay ops") }
}

package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.op.SkillOp
import com.astarworks.astera.core.domain.skillengine.op.WorldOp

/** Decoders of the world family. The manager registers them with `OpDecoderRegistry`. */
public fun worldDecoders(blocks: IBlockKeyCatalog): List<OpDecoder> = listOf(WorldOpDecoder(blocks))

/**
 * ```yaml
 * - {id: wall, op: place_shape, shape: wall, block: oak_planks, width: 5, height: 3, revert_after: 400}
 * - {id: dig,  op: break_area, shape: sphere, radius: 2.5, length: 3, tool_class: PICKAXE, block_damage: 40, power: 2, breaks: true}
 * ```
 * `at` is a frame point (`caster`, `contact.point`, `blade_a.tip`) or a world vector; default `caster` (feet).
 * `breaks: false` makes a destructive op inert (per-skill block flag). Sizes are literals.
 */
internal class WorldOpDecoder(private val blocks: IBlockKeyCatalog) : OpDecoder {
    override val section: OpSection = OpSection.GAMEPLAY
    override val opNames: Set<String> = setOf("set_block", "remove_block", "break_area", "place_shape")

    override fun decode(op: String, id: String, whenExpr: Value<Boolean>?, reader: OpReader): SkillOp {
        val at = location(reader)
        val revert = reader.number("revert_after")
        val outside = reader.bool("outside_arena") ?: false
        return when (op) {
            "set_block" -> WorldOp.SetBlock(id, whenExpr, at, blockKey(reader), revert, outside)
            "remove_block" -> WorldOp.RemoveBlock(id, whenExpr, at, reader.bool("breaks") ?: true, revert, outside)
            "break_area" -> WorldOp.BreakArea(
                id = id,
                whenExpr = whenExpr,
                at = at,
                shape = enumOf(reader, "shape", WorldOp.AreaShape.SPHERE),
                radius = literal(reader, "radius", 2.0),
                length = literal(reader, "length", 4.0),
                width = literal(reader, "width", 1.0),
                height = literal(reader, "height", 2.0),
                toolClass = reader.string("tool_class")?.uppercase(),
                blockDamage = reader.int("block_damage") ?: 0,
                power = literal(reader, "power", 3.0),
                breaksBlocks = reader.bool("breaks") ?: true,
                revertAfterTicks = revert,
                outsideArena = outside,
                staged = reader.bool("staged") ?: true,
            )
            "place_shape" -> WorldOp.PlaceShape(
                id = id,
                whenExpr = whenExpr,
                at = at,
                shape = enumOf(reader, "shape", WorldOp.BuildShape.WALL),
                block = blockKey(reader),
                width = reader.int("width") ?: 3,
                height = reader.int("height") ?: 3,
                length = reader.int("length") ?: 3,
                consumeItem = reader.string("consume_item"),
                revertAfterTicks = revert,
                outsideArena = outside,
            )
            else -> reader.fail("not a world op: $op")
        }
    }

    private fun blockKey(reader: OpReader): String {
        val key = reader.requireString("block")
        if (!blocks.exists(key)) reader.fail("block: unknown block key '$key' (not a vanilla material and not a registered custom block)")
        return key
    }

    private fun location(reader: OpReader): LocationSpec {
        val raw = reader.raw("at") ?: return LocationSpec.AtPoint(PointRef("caster"))
        val isText = raw is String
        val isExpr = isText && ((raw as String).startsWith("=") || raw.startsWith("\${"))
        return if (isText && !isExpr) {
            LocationSpec.AtPoint(reader.point("at") ?: reader.fail("at: unreadable point"))
        } else {
            LocationSpec.AtValue(reader.vec3("at") ?: reader.fail("at: expected a point or a vector"))
        }
    }

    private fun literal(reader: OpReader, key: String, default: Double): Double =
        when (val v = reader.number(key)) {
            null -> default
            is Value.Literal -> v.value
            else -> reader.fail("$key must be a literal number")
        }

    private inline fun <reified E : Enum<E>> enumOf(reader: OpReader, key: String, default: E): E {
        val text = reader.string(key) ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(text, ignoreCase = true) }
            ?: reader.fail("$key: unknown '$text' (expected ${enumValues<E>().joinToString { it.name.lowercase() }})")
    }
}

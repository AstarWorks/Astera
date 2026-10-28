package com.astarworks.astera.core.application.skillengine.load.ops.world

import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.world.AreaShape
import com.astarworks.astera.core.domain.skillengine.world.BuildShape
import org.joml.Vector3d

/**
 * Shared field types and lowering for the world ops (`break_area`, `place_shape`, `explode`, `lightning`; ADR-0303 10.1c).
 * Every number is a literal: the table row is static, the gateway decides what happens in the world.
 */
internal object WorldOps {
    /** Live names that need no declaration, read from the caster / event at play time. */
    private val BUILTIN_ROOTS = setOf("caster", "contact", "target", "victim", "attacker", "aim", "cast", "world")

    fun intIn(min: Int, max: Int): FieldType<Int> = object : FieldType<Int> {
        override val label = "integer $min..$max"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Int {
            val n = raw as? Number ?: ctx.mismatch(path, label, raw)
            if (n.toDouble() % 1.0 != 0.0) ctx.fail(path, "expected an integer, found $n")
            if (n.toInt() !in min..max) ctx.fail(path, "must be $min..$max, found $n")
            return n.toInt()
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "integer", "minimum" to min, "maximum" to max)
    }

    fun positive(label: String): FieldType<Double> = object : FieldType<Double> {
        override val label = label
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Double {
            val n = (raw as? Number ?: ctx.mismatch(path, this.label, raw)).toDouble()
            if (n <= 0.0) ctx.fail(path, "must be > 0, found $n")
            return n
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "number", "exclusiveMinimum" to 0)
    }

    fun nonNegative(label: String): FieldType<Double> = object : FieldType<Double> {
        override val label = label
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Double {
            val n = (raw as? Number ?: ctx.mismatch(path, this.label, raw)).toDouble()
            if (n < 0.0) ctx.fail(path, "must be >= 0, found $n")
            return n
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "number", "minimum" to 0)
    }

    val ripple: FieldType<Int> = intIn(0, 6000)
    val revert: FieldType<Int> = intIn(1, 6000)
    val blockDamage: FieldType<Int> = intIn(0, 2000)
    private val size: FieldType<Int> = intIn(1, 64)

    /** `item_id` per placed cell, or `none`. */
    val consume: FieldType<String?> = object : FieldType<String?> {
        override val label = "item id or none"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): String? {
            val s = raw as? String ?: ctx.mismatch(path, label, raw)
            if (s.isBlank() || s.any { it.isWhitespace() }) ctx.fail(path, "expected an item id or none, found '$s'")
            return if (s == "none") null else s
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "string", "pattern" to "^\\S+$")
    }

    private val sphereRadius = Field.required("radius", positive("number > 0"), "sphere radius in blocks")
    private val sphereAhead = Field.required("ahead", nonNegative("number >= 0"), "centre = origin + look * ahead")
    private val sphere = Fields.of("shape.sphere", sphereRadius, sphereAhead)
    private val lineLength = Field.required("length", positive("number > 0"), "blocks along the look")
    private val lineWidth = Field.required("width", positive("number > 0"), "diameter of the ball filled at each 0.5-block step")
    private val line = Fields.of("shape.line", lineLength, lineWidth)
    private val boxWidth = Field.required("width", size, "blocks, centred on the caster")
    private val boxHeight = Field.required("height", size, "blocks up from the feet")
    private val boxLength = Field.required("length", size, "blocks ahead")
    private val box = Fields.of("shape.box", boxWidth, boxHeight, boxLength)

    val areaShape: FieldType<AreaShape> = object : FieldType<AreaShape> {
        override val label = "{sphere: {radius, ahead}} | {line: {length, width}} | {box: {width, height, length}}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): AreaShape {
            val (kind, body) = single(raw, ctx, path, label)
            return when (kind) {
                "sphere" -> sphere.read(body, ctx, "$path.sphere").let { AreaShape.Sphere(it[sphereRadius], it[sphereAhead]) }
                "line" -> line.read(body, ctx, "$path.line").let { AreaShape.Line(it[lineLength], it[lineWidth]) }
                "box" -> box.read(body, ctx, "$path.box").let { AreaShape.Box(it[boxWidth], it[boxHeight], it[boxLength]) }
                else -> ctx.fail(path, "unknown shape '$kind', expected sphere, line or box")
            }
        }
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "oneOf" to listOf(
                linkedMapOf("type" to "object", "required" to listOf("sphere"), "properties" to linkedMapOf("sphere" to sphere.jsonSchema())),
                linkedMapOf("type" to "object", "required" to listOf("line"), "properties" to linkedMapOf("line" to line.jsonSchema())),
                linkedMapOf("type" to "object", "required" to listOf("box"), "properties" to linkedMapOf("box" to box.jsonSchema())),
            ),
        )
    }

    private val wallWidth = Field.required("width", size, "blocks, centred on the caster")
    private val wallHeight = Field.required("height", size, "blocks up from the feet")
    private val wall = Fields.of("shape.wall", wallWidth, wallHeight)
    private val bridgeLength = Field.required("length", size, "blocks ahead, starting 1 ahead at feet - 1")
    private val bridgeWidth = Field.required("width", size, "blocks")
    private val bridge = Fields.of("shape.bridge", bridgeLength, bridgeWidth)
    private val pillarHeight = Field.required("height", size, "blocks, 2 ahead")
    private val pillar = Fields.of("shape.pillar", pillarHeight)
    private val platformWidth = Field.required("width", size, "blocks")
    private val platformLength = Field.required("length", size, "blocks")
    private val platform = Fields.of("shape.platform", platformWidth, platformLength)

    val buildShape: FieldType<BuildShape> = object : FieldType<BuildShape> {
        override val label = "{wall: {width, height}} | {bridge: {length, width}} | {pillar: {height}} | {platform: {width, length}}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): BuildShape {
            val (kind, body) = single(raw, ctx, path, label)
            return when (kind) {
                "wall" -> wall.read(body, ctx, "$path.wall").let { BuildShape.Wall(it[wallWidth], it[wallHeight]) }
                "bridge" -> bridge.read(body, ctx, "$path.bridge").let { BuildShape.Bridge(it[bridgeLength], it[bridgeWidth]) }
                "pillar" -> pillar.read(body, ctx, "$path.pillar").let { BuildShape.Pillar(it[pillarHeight]) }
                "platform" -> platform.read(body, ctx, "$path.platform").let { BuildShape.Platform(it[platformWidth], it[platformLength]) }
                else -> ctx.fail(path, "unknown shape '$kind', expected wall, bridge, pillar or platform")
            }
        }
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "oneOf" to listOf("wall" to wall, "bridge" to bridge, "pillar" to pillar, "platform" to platform).map { (k, f) ->
                linkedMapOf("type" to "object", "required" to listOf(k), "properties" to linkedMapOf(k to f.jsonSchema()))
            },
        )
    }

    private fun single(raw: Any?, ctx: GrammarContext, path: String, label: String): Pair<String, Any?> {
        val m = raw as? Map<*, *> ?: ctx.mismatch(path, label, raw)
        if (m.size != 1) ctx.fail(path, "expected exactly one shape key, found ${m.keys}")
        val e = m.entries.single()
        return (e.key as? String ?: ctx.mismatch(path, label, raw)) to e.value
    }

    /** Position of the op: a declared anchor (its instance), or a caster / event point the player reads live. */
    fun position(name: String, lc: LowerContext): Result<Val<Vector3d>, SkillDecodeError> {
        lc.anchors.resolve(name)?.let { return Result.Success(Val.Live<Vector3d>(it.instance.text, null)) }
        if (name.substringBefore('.') in BUILTIN_ROOTS) return Result.Success(Val.Live<Vector3d>(name, null))
        return Result.Failure(SkillDecodeError.UndefinedReference(lc.skillId, lc.src.value, name))
    }

    /** One `Action.World` row at this occurrence's own tick; shapes face where the caster looks. */
    fun lower(op: OpId, anchor: String, payload: Map<String, Val<Any>>, lc: LowerContext): Result<Lowered, SkillDecodeError> =
        position(anchor, lc).map { origin ->
            val action = Action.World(op, origin, Val.Live<Vector3d>("caster.forward", null), payload)
            Lowered(rows = listOf(Row(lc.clockOffset, lc.self, action, lc.src)))
        }

    val anchorField: Field<String> = Field.required("anchor", com.astarworks.astera.core.application.skillengine.load.grammar.Types.id, "where it happens: a declared anchor, or caster / caster.eye / caster.feet / contact.point")
    val outsideArena: Field<Boolean> = Field.required("outside_arena", com.astarworks.astera.core.application.skillengine.load.ops.gameplay.GameplayOps.flag, "true lifts the arena-only edit restriction")
}

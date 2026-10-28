package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.LawSegmenter
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.DecodeAbort
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.AxisResolver
import com.astarworks.astera.core.application.skillengine.load.grammar.Every
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.LocalAxes
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotation
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotations
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.TimeAt
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaws
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.joml.Quaterniond
import org.joml.Vector3d

public enum class DisplayKind { ITEM, BLOCK, TEXT, SPRITE }

/**
 * Decoded `display` op (ADR-0303 2.1). Every field is a vanilla Display / ItemDisplay / BlockDisplay / TextDisplay field; a field that
 * is not written has the vanilla (identity) default of its spec entry, never an engine-side surprise (2.0b). Numeric `${param}`s are
 * folded to literals at decode; what stays a [Value] is a CEL expression or a param the context could not resolve.
 */
public class DisplayOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val kind: DisplayKind,
    public val anchor: String,
    public val offset: FrameVec?,
    /** Item model id, block id, sprite id, or text (by [kind]). */
    public val subject: Value<String>,
    public val context: String,
    public val textStyle: TextStyle?,
    public val spriteSize: Pair<Value<Double>, Value<Double>>?,
    public val spritePivot: String?,
    public val tint: Value<String>?,
    public val translation: Value<Vec3>,
    public val rotate: Rotation,
    public val scale: Value<Vec3>,
    public val right: Rotation,
    public val yaw: Value<Double>,
    public val pitch: Value<Double>,
    public val billboard: String,
    public val brightness: Pair<Value<Double>, Value<Double>>?,
    public val viewRange: Value<Double>,
    public val glowColor: Value<String>?,
    public val interpolationDelay: Value<Double>,
    public val interpolationDuration: Value<Double>,
    public val teleportDuration: Value<Double>,
    /** Laws decoded by [MotionLaws]; segmented into `Key` rows (and `Set` rows for a fade) by [LawSegmenter] at compile. */
    public val motion: List<MotionLaw>,
) : OpNode

/** The `kind: text` fields (TextDisplay). */
public class TextStyle(
    public val background: Value<String>?,
    public val alignment: String,
    public val lineWidth: Value<Double>,
    public val textOpacity: Value<Double>,
    public val seeThrough: Value<Boolean>,
    public val shadow: Value<Boolean>,
)

public object DisplayOpSpec : OpSpec<DisplayOp> {
    override val name: String = "display"
    override val section: OpSection = OpSection.PRESENTATION
    override val timing: TimingKind? = TimingKind.EXTENDED

    private val CONTEXTS = arrayOf(
        "none", "thirdperson_lefthand", "thirdperson_righthand", "firstperson_lefthand", "firstperson_righthand",
        "head", "gui", "ground", "fixed",
    )

    private val SIZE = object : FieldType<Pair<Value<Double>, Value<Double>>> {
        override val label = "[w, h] in blocks"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Pair<Value<Double>, Value<Double>> {
            val l = raw as? List<*> ?: ctx.mismatch(path, label, raw)
            if (l.size != 2) ctx.fail(path, "size is [w, h] in blocks (found ${l.size} entries)")
            return Types.number.decode(l[0], ctx, "$path[0]") to Types.number.decode(l[1], ctx, "$path[1]")
        }
        override fun schema(): Map<String, Any?> =
            linkedMapOf("type" to "array", "items" to Types.number.schema(), "minItems" to 2, "maxItems" to 2)
    }

    private val MOTION = object : FieldType<List<MotionLaw>> {
        override val label = "list of motion laws"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<MotionLaw> = MotionLaws.readLaws(raw, ctx, path)
        override fun schema(): Map<String, Any?> = MotionLaws.jsonSchema()
    }

    private val kindF = Field.required("kind", Types.enumOf("item", "block", "text", "sprite"), "item | block | text | sprite")
    private val anchorF = Field.required("anchor", Types.id, "anchor name; the entity LOCATION is the anchor's world origin plus offset (2.3)")
    private val offsetF = Field.optional("offset", Types.nullable(FrameVecs.type), null, "{forward, up, left} in the anchor's axes, folded into the entity location; absent = none")

    // item
    private val itemF = Field.optional("item", Types.nullable(Types.id), null, "kind item: item_model id (main_hand = the held item's model, resolved at cast start)")
    private val contextF = Field.optional("context", Types.enumOf(*CONTEXTS), "fixed", "kind item: ItemDisplayContext, decision default fixed")

    // block
    private val blockF = Field.optional("block", Types.nullable(Types.id), null, "kind block: block id, e.g. minecraft:white_concrete")

    // text
    private val textF = Field.optional("text", Types.nullable(Types.text), null, "kind text: the text")
    private val backgroundF = Field.optional("background", Types.nullable(Types.text), null, "kind text: #aarrggbb; absent = vanilla default background")
    private val alignmentF = Field.optional("alignment", Types.enumOf("center", "left", "right"), "center", "kind text: vanilla default center")
    private val lineWidthF = Field.optional("line_width", Types.number, Value.Literal(200.0), "kind text: vanilla default 200")
    private val textOpacityF = Field.optional("text_opacity", Types.number, Value.Literal(255.0), "kind text: 0..255, vanilla default 255 (opaque)")
    private val seeThroughF = Field.optional("see_through", Types.bool, Value.Literal(false), "kind text: vanilla default false")
    private val shadowF = Field.optional("shadow", Types.bool, Value.Literal(false), "kind text: vanilla default false")

    // sprite
    private val spriteF = Field.optional("sprite", Types.nullable(Types.id), null, "kind sprite: content/sprites id")
    private val sizeF = Field.optional("size", Types.nullable(SIZE), null, "kind sprite: [w, h] in BLOCKS (2.1)")
    private val pivotF = Field.optional("pivot", Types.enumOf("center", "origin"), "center", "kind sprite: centre compensation macro (2.1), decision default center")
    private val tintF = Field.optional("tint", Types.nullable(Types.text), null, "kind sprite: tint #rrggbb or #aarrggbb; absent = untinted")

    // Transformation(translation, left_rotation, scale, right_rotation)
    private val translationF = Field.optional("translation", Types.worldVec3, Value.Literal(Vec3(0.0, 0.0, 0.0)), "vanilla translation, entity-local axes (never rotated by left_rotation, 2.3)")
    private val rotateF = Field.optional("rotate", Rotations.type, Rotation.Turns(emptyList()), "left_rotation: named-axis list or quaternion; absent = identity")
    private val scaleF = Field.optional("scale", Types.worldVec3, Value.Literal(Vec3(1.0, 1.0, 1.0)), "vanilla scale; sprites use size")
    private val rightF = Field.optional("right", Rotations.type, Rotation.Whole(Value.Literal(Quaternion.IDENTITY)), "right_rotation, quaternion [x, y, z, w]; absent = identity")
    private val yawF = Field.optional("yaw", Types.number, Value.Literal(0.0), "entity yaw, pinned at spawn")
    private val pitchF = Field.optional("pitch", Types.number, Value.Literal(0.0), "entity pitch, pinned at spawn")
    private val billboardF = Field.optional("billboard", Types.enumOf("fixed", "vertical", "horizontal", "center"), "fixed", "vanilla Billboard; fixed is the decision default")
    private val brightnessBlock = Field.required("block", Types.number, "block light 0..15")
    private val brightnessSky = Field.required("sky", Types.number, "sky light 0..15")
    private val brightnessFields = Fields.of("brightness", brightnessBlock, brightnessSky)
    private val brightnessF = Field.optional("brightness", Types.nullable(Types.nested(brightnessFields)), null, "{block, sky}; absent = world light")
    private val viewRangeF = Field.optional("view_range", Types.number, Value.Literal(1.0), "vanilla view range multiplier")
    private val glowColorF = Field.optional("glow_color", Types.nullable(Types.text), null, "#rrggbb glow override; absent = none")
    private val interpDelayF = Field.optional("interpolation_delay", Types.number, Value.Literal(0.0), "engine-owned after spawn (section 4); written only for hand-tuned loops")
    private val interpDurationF = Field.optional("interpolation_duration", Types.number, Value.Literal(0.0), "engine-owned after spawn (section 4)")
    private val teleportDurationF = Field.optional("teleport_duration", Types.number, Value.Literal(0.0), "engine-owned after spawn (section 4)")
    private val motionF = Field.optional("motion", Types.nullable(MOTION), null, "rate laws and keys (section 3)")

    /** Fields that belong to exactly one kind; written on another kind they are a load error (nothing is silently dropped). */
    private val kindOnly: Map<String, List<Field<*>>> = mapOf(
        "item" to listOf(itemF, contextF),
        "block" to listOf(blockF),
        "text" to listOf(textF, backgroundF, alignmentF, lineWidthF, textOpacityF, seeThroughF, shadowF),
        "sprite" to listOf(spriteF, sizeF, pivotF, tintF),
    )

    override val fields: Fields = Fields.of(
        "display", kindF, anchorF, offsetF,
        itemF, contextF, blockF,
        textF, backgroundF, alignmentF, lineWidthF, textOpacityF, seeThroughF, shadowF,
        spriteF, sizeF, pivotF, tintF,
        translationF, rotateF, scaleF, rightF, yawF, pitchF, billboardF, brightnessF, viewRangeF, glowColorF,
        interpDelayF, interpDurationF, teleportDurationF, motionF,
    )

    // ------------------------------------------------------------------------------------------------ decode

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<DisplayOp, SkillDecodeError> =
        Grammar.guard {
            val p = id.value
            val t = timing ?: ctx.fail(p, "display is a timed op: the timing block is missing")
            if (t.every == null && t.span == null && t.until == null) {
                ctx.fail(p, "display needs an end: one of for, until, for: cast (ADR-0303 section 5)")
            }
            val kind = DisplayKind.valueOf(values[kindF].uppercase())
            for ((otherKind, fs) in kindOnly) {
                if (otherKind == values[kindF]) continue
                fs.firstOrNull { values.isPresent(it) }?.let {
                    ctx.fail("$p.${it.name}", "'${it.name}' belongs to kind $otherKind, not to kind ${values[kindF]}")
                }
            }
            if (kind == DisplayKind.SPRITE && values.isPresent(scaleF)) {
                ctx.fail("$p.scale", "UnknownKey: a sprite is sized with 'size' (blocks), not 'scale'")
            }
            val billboard = values[billboardF]
            if (billboard != "fixed" && values.isPresent(translationF)) {
                val tr = values[translationF]
                if (!(tr is Value.Literal && tr.value == Vec3(0.0, 0.0, 0.0))) {
                    ctx.fail("$p.translation", "a billboarded display carries its offset in the entity location ('offset'), never in translation (2.3)")
                }
            }
            val subject: Value<String> = when (kind) {
                DisplayKind.ITEM -> values[itemF]?.let { Value.Literal(it) } ?: ctx.abortMissing(p, "item")
                DisplayKind.BLOCK -> values[blockF]?.let { Value.Literal(it) } ?: ctx.abortMissing(p, "block")
                DisplayKind.TEXT -> values[textF]?.let { foldText(it, ctx) } ?: ctx.abortMissing(p, "text")
                DisplayKind.SPRITE -> values[spriteF]?.let { Value.Literal(it) } ?: ctx.abortMissing(p, "sprite")
            }
            val size = if (kind == DisplayKind.SPRITE) {
                val s = values[sizeF] ?: ctx.abortMissing(p, "size")
                val w = foldNum(s.first, ctx)
                val h = foldNum(s.second, ctx)
                checkPositive(w, "$p.size[0]", ctx)
                checkPositive(h, "$p.size[1]", ctx)
                w to h
            } else {
                null
            }
            val tint = values[tintF]?.let { foldText(it, ctx).also { c -> checkHex(c, "$p.tint", ctx, allowAlpha = true) } }
            val glow = values[glowColorF]?.let { foldText(it, ctx).also { c -> checkHex(c, "$p.glow_color", ctx, allowAlpha = false) } }
            val background = values[backgroundF]?.let { foldText(it, ctx).also { c -> checkHex(c, "$p.background", ctx, allowAlpha = true) } }

            val style = if (kind == DisplayKind.TEXT) {
                TextStyle(
                    background, values[alignmentF],
                    wholeNum(values[lineWidthF], "$p.line_width", ctx, 1.0, null),
                    wholeNum(values[textOpacityF], "$p.text_opacity", ctx, 0.0, 255.0),
                    foldBool(values[seeThroughF], ctx), foldBool(values[shadowF], ctx),
                )
            } else {
                null
            }

            val brightness = values[brightnessF]?.let { b ->
                val blk = wholeNum(b[brightnessBlock], "$p.brightness.block", ctx, 0.0, 15.0)
                val sky = wholeNum(b[brightnessSky], "$p.brightness.sky", ctx, 0.0, 15.0)
                blk to sky
            }
            val viewRange = foldNum(values[viewRangeF], ctx).also { checkPositive(it, "$p.view_range", ctx) }

            DisplayOp(
                id, src, whenExpr, foldTiming(t, ctx), kind, values[anchorF], values[offsetF],
                subject, values[contextF], style, size, if (kind == DisplayKind.SPRITE) values[pivotF] else null, tint,
                values[translationF], values[rotateF], values[scaleF], values[rightF],
                foldNum(values[yawF], ctx), foldNum(values[pitchF], ctx), billboard, brightness, viewRange, glow,
                wholeNum(values[interpDelayF], "$p.interpolation_delay", ctx, 0.0, null),
                wholeNum(values[interpDurationF], "$p.interpolation_duration", ctx, 0.0, null),
                wholeNum(values[teleportDurationF], "$p.teleport_duration", ctx, 0.0, null),
                values[motionF] ?: emptyList(),
            )
        }

    // ------------------------------------------------------------------------------------------------ lower

    override fun lower(op: DisplayOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val t = op.timing ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "display lost its timing block"))
        val instance = instanceOf(lc)
        val src = lc.src
        return Grammar.guard {
            val gc = GrammarContext(lc.skillId)
            val itemAxes = if (op.kind == DisplayKind.ITEM) lc.frames.item((op.subject as? Value.Literal<String>)?.value ?: "")?.partFor(null)?.axes else null
            val axes = AxisResolver { n -> LocalAxes.axis(n) ?: if (n == "face") FACE else itemAxes?.get(n) }
            for ((r, f) in listOf(op.rotate to "rotate", op.right to "right")) {
                if (r is Rotation.Turns) r.turns.forEachIndexed { i, turn ->
                    if (turn.frame != null) gc.fail("${src.value}.$f[$i].axis", "a frame-qualified axis ('${turn.frame}.${turn.axis}') is not supported on a display rotation; use a plain or item axis")
                }
            }
            val left = op.rotate.toValue(gc, "${src.value}.rotate", axes)
            val right = op.right.toValue(gc, "${src.value}.right", axes)

            val sprite = if (op.kind == DisplayKind.SPRITE) spriteScale(op, lc) else null
            val pose = Pose(
                position = op.offset?.toLocal()?.let { vecCell(it) } ?: Val.Known(Vector3d(0.0, 0.0, 0.0)),
                rotation = quatCell(left),
                scale = sprite?.scale ?: vecCell(op.scale),
            )
            val spawn = Row(0, instance, Action.Spawn(spawnKind(op.kind), anchorInstance(op, lc), op.anchor, pose, look(op, right, sprite)), src)

            val rows = ArrayList<Row>()
            rows += spawn
            val subTables = ArrayList<SubTable>()

            val end = endTick(op, t, lc)
            if (end != null) rows += Row(end, instance, Action.End(instance), src)
            t.until?.let { ev ->
                // `until` is a mask (2.0): the event's sub-table ends this instance the first time it fires.
                val hi = lc.clockOffset + (end ?: lc.config.maxLifeTicks)
                subTables += SubTable(
                    EventPath(ev), lc.state, TickRange(lc.clockOffset, maxOf(lc.clockOffset, hi)), 1,
                    listOf(Row(0, instance, Action.End(instance), src)), emptyList(),
                )
            }

            var writes = emptyMap<InstanceId, com.astarworks.astera.core.domain.skillengine.table.WriteStats>()
            // The anchor's own laws ride first: left_rotation = anchor.rot(t) * rider (item 16).
            val laws = lc.anchors.laws(op.anchor) + op.motion
            if (laws.isNotEmpty()) {
                val moved = LawSegmenter.segments(instance, laws, lc, FADE_FIELD)
                when (moved) {
                    is Result.Failure -> throw DecodeAbort(moved.error)
                    is Result.Success -> {
                        rows += moved.value.rows
                        subTables += moved.value.subTables
                        writes = moved.value.writes
                    }
                }
            }
            Lowered(rows, subTables, listOf(instance), emptyList(), writes)
        }
    }

    /** A fade law writes the vanilla TextDisplay opacity field (text and sprite displays; item / block use `grow`). */
    private const val FADE_FIELD = "text_opacity"

    /** Sprite sizing (item 10): natural quad = px * 0.025 blocks; `scale` = size / natural, so the gateway computes nothing. */
    private class SpriteScale(val scale: Val<Vector3d>)

    private fun spriteScale(op: DisplayOp, lc: LowerContext): SpriteScale {
        val id = (op.subject as? Value.Literal<String>)?.value
            ?: throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "a sprite id is a literal"))
        val natural = lc.sprites.natural(id)
            ?: throw DecodeAbort(SkillDecodeError.UndefinedReference(lc.skillId, lc.src.value, id))
        val nw = natural.w * SPRITE_BLOCKS_PER_PX
        val nh = natural.h * SPRITE_BLOCKS_PER_PX
        val (w, h) = op.spriteSize!!
        val scale: Val<Vector3d> =
            if (w is Value.Literal && h is Value.Literal) {
                Val.Known(Vector3d(w.value / nw, h.value / nh, 1.0))
            } else {
                Val.Live("vec(${sourceOf(w)} / $nw, ${sourceOf(h)} / $nh, 1.0)", null)
            }
        return SpriteScale(scale)
    }

    private const val SPRITE_BLOCKS_PER_PX = 0.025

    // ------------------------------------------------------------------------------------------------ helpers

    /** Direction the display's front faces, in the entity-local basis (x = left, y = up, z = forward): the roll axis of a billboard. */
    private val FACE = Vec3(0.0, 0.0, 1.0)

    /** The op's own instance id: the unroller already indexes a repeating op (item 5). */
    private fun instanceOf(lc: LowerContext): InstanceId = lc.self

    /** The anchor as an instance when it names an op of this cast (`ball`); null for a named skill / caster anchor. */
    private fun anchorInstance(op: DisplayOp, lc: LowerContext): InstanceId? = lc.anchors.resolve(op.anchor)?.instance

    /** Tick (relative to the occurrence) the instance ends at; null = lives until the cast ends or an `until` event. */
    private fun endTick(op: DisplayOp, t: Timing, lc: LowerContext): Int? {
        val src = lc.src.value
        val ticks: Int? = if (t.every != null) {
            val lasts = t.lasts ?: throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, src, "an every display needs lasts"))
            staticTicks(lc, "$src.lasts", "lasts", lasts)
        } else {
            when (val s = t.span) {
                is Span.Ticks -> staticTicks(lc, "$src.for", "for", s.ticks)
                Span.UntilCastEnds, null -> null
            }
        }
        if (ticks != null && ticks < 1) {
            throw DecodeAbort(
                SkillDecodeError.Invalid(lc.skillId, src, "a display lives at least 1 tick (was $ticks)"),
            )
        }
        return ticks
    }

    private fun staticTicks(lc: LowerContext, path: String, field: String, v: Value<Double>): Int =
        when (val r = lc.requireStaticTiming(path, field, v)) {
            is Result.Success -> r.value
            is Result.Failure -> throw DecodeAbort(r.error)
        }

    private fun spawnKind(k: DisplayKind): SpawnKind = when (k) {
        DisplayKind.ITEM -> SpawnKind.ITEM
        DisplayKind.BLOCK -> SpawnKind.BLOCK
        DisplayKind.TEXT -> SpawnKind.TEXT
        DisplayKind.SPRITE -> SpawnKind.SPRITE
    }

    /** The vanilla fields of the spawn payload, one key per vanilla field (printed by the dump; a missing optional key = vanilla default). */
    private fun look(op: DisplayOp, right: Value<Quaternion>, sprite: SpriteScale?): Look {
        val f = LinkedHashMap<String, Val<Any>>()
        when (op.kind) {
            DisplayKind.ITEM -> {
                f["item"] = cell(op.subject)
                f["context"] = Val.Known(op.context)
            }
            DisplayKind.BLOCK -> f["block"] = cell(op.subject)
            DisplayKind.TEXT -> {
                val s = op.textStyle!!
                f["text"] = cell(op.subject)
                s.background?.let { f["background"] = cell(it) }
                f["alignment"] = Val.Known(s.alignment)
                f["line_width"] = cell(s.lineWidth)
                f["text_opacity"] = cell(s.textOpacity)
                f["see_through"] = cell(s.seeThrough)
                f["shadow"] = cell(s.shadow)
            }
            DisplayKind.SPRITE -> {
                val (w, h) = op.spriteSize!!
                f["sprite"] = cell(op.subject)
                f["size"] = vecCell(w, h)
                f["pivot"] = Val.Known(op.spritePivot!!)
                f["scale"] = sprite!!.scale
                op.tint?.let { f["tint"] = cell(it) }
            }
        }
        f["translation"] = vecCell(op.translation)
        f["right_rotation"] = quatCell(right)
        f["yaw"] = cell(op.yaw)
        f["pitch"] = cell(op.pitch)
        f["billboard"] = Val.Known(op.billboard)
        op.brightness?.let { (b, s) -> f["brightness_block"] = cell(b); f["brightness_sky"] = cell(s) }
        f["view_range"] = cell(op.viewRange)
        op.glowColor?.let { f["glow_color"] = cell(it) }
        f["interpolation_delay"] = cell(op.interpolationDelay)
        f["interpolation_duration"] = cell(op.interpolationDuration)
        f["teleport_duration"] = cell(op.teleportDuration)
        return Look(f)
    }

    private fun <T> cell(v: Value<T>): Val<T> = when (v) {
        is Value.Literal -> Val.Known(v.value)
        is Value.ParamRef -> Val.Live("\${${v.name}}", null)
        is Value.Expr -> Val.Live("= ${v.source}", null)
    }

    private fun vecCell(v: Value<Vec3>): Val<Vector3d> = when (v) {
        is Value.Literal -> Val.Known(Vector3d(v.value.x, v.value.y, v.value.z))
        else -> cell(v).let { c -> c as Val<Vector3d> }
    }

    /** A sprite size [w, h] as the vector (w, h, 1). */
    private fun vecCell(w: Value<Double>, h: Value<Double>): Val<Vector3d> =
        if (w is Value.Literal && h is Value.Literal) {
            Val.Known(Vector3d(w.value, h.value, 1.0))
        } else {
            Val.Live("vec(${sourceOf(w)}, ${sourceOf(h)}, 1.0)", null)
        }

    private fun quatCell(q: Value<Quaternion>): Val<Quaterniond> = when (q) {
        is Value.Literal -> Val.Known(Quaterniond(q.value.x, q.value.y, q.value.z, q.value.w))
        else -> cell(q).let { c -> c as Val<Quaterniond> }
    }

    private fun sourceOf(v: Value<Double>): String = when (v) {
        is Value.Literal -> v.value.toString()
        is Value.ParamRef -> v.name
        is Value.Expr -> "(${v.source})"
    }

    // decode-time folding of numeric / text / bool params to literals

    private fun foldNum(v: Value<Double>, ctx: GrammarContext): Value<Double> =
        if (v is Value.ParamRef) ctx.resolveNumber(v)?.let { Value.Literal(it) } ?: v else v

    private fun foldText(v: Value<String>, ctx: GrammarContext): Value<String> =
        if (v is Value.ParamRef) (ctx.params?.get(v.name) as? ParamValue.Text)?.let { Value.Literal(it.value) } ?: v else v

    private fun foldBool(v: Value<Boolean>, ctx: GrammarContext): Value<Boolean> =
        if (v is Value.ParamRef) (ctx.params?.get(v.name) as? ParamValue.Flag)?.let { Value.Literal(it.value) } ?: v else v

    private fun foldTiming(t: Timing, ctx: GrammarContext): Timing = Timing(
        at = when (val a = t.at) {
            is TimeAt.Tick -> TimeAt.Tick(foldNum(a.ticks, ctx))
            else -> a
        },
        span = when (val s = t.span) {
            is Span.Ticks -> Span.Ticks(foldNum(s.ticks, ctx))
            else -> s
        },
        every = when (val e = t.every) {
            is Every.Fixed -> Every.Fixed(foldNum(e.ticks, ctx))
            is Every.Eased -> Every.Eased(foldNum(e.from, ctx), foldNum(e.to, ctx), e.ease)
            null -> null
        },
        times = t.times?.let { foldNum(it, ctx) },
        until = t.until,
        lasts = t.lasts?.let { foldNum(it, ctx) },
    )

    /** A whole number in [lo, hi] when it is a literal; a CEL / unresolved value is checked when it is evaluated. */
    private fun wholeNum(v: Value<Double>, path: String, ctx: GrammarContext, lo: Double, hi: Double?): Value<Double> {
        val f = foldNum(v, ctx)
        if (f is Value.Literal) {
            val d = f.value
            if (d != Math.floor(d) || d < lo || (hi != null && d > hi)) {
                ctx.fail(path, "must be a whole number" + (if (hi != null) " in $lo..$hi" else " >= $lo") + " (was $d)")
            }
        }
        return f
    }

    private fun checkPositive(v: Value<Double>, path: String, ctx: GrammarContext) {
        if (v is Value.Literal && v.value <= 0.0) ctx.fail(path, "must be > 0 (was ${v.value})")
    }

    private fun checkHex(v: Value<String>, path: String, ctx: GrammarContext, allowAlpha: Boolean) {
        if (v !is Value.Literal) return
        val h = v.value.removePrefix("#")
        val ok = v.value.startsWith("#") && h.toLongOrNull(16) != null && (h.length == 6 || (allowAlpha && h.length == 8))
        if (!ok) ctx.fail(path, "'${v.value}' is not " + if (allowAlpha) "#rrggbb or #aarrggbb" else "#rrggbb")
    }

    private fun GrammarContext.abortMissing(path: String, field: String): Nothing =
        fail("$path.$field", "kind needs '$field'")
}

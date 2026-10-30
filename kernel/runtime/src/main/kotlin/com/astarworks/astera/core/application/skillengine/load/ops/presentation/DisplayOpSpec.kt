package com.astarworks.astera.core.application.skillengine.load.ops.presentation

import com.astarworks.astera.core.application.skillengine.compose.AnchorNames
import com.astarworks.astera.core.application.skillengine.compose.LawSegmenter
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.compose.PoseCompose
import com.astarworks.astera.core.application.skillengine.load.MeshModels
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
import com.astarworks.astera.core.application.skillengine.compose.ExprClass
import com.astarworks.astera.core.application.skillengine.expr.cel.ExprClassifier
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.application.skillengine.load.motion.AlignMotion
import com.astarworks.astera.core.application.skillengine.load.motion.KeysMotion
import com.astarworks.astera.core.application.skillengine.load.motion.OrbitMotion
import com.astarworks.astera.core.application.skillengine.load.motion.RateMotion
import com.astarworks.astera.core.domain.skillengine.motion.SpinRate
import com.astarworks.astera.core.application.skillengine.load.motion.LawKey
import com.astarworks.astera.core.application.skillengine.load.motion.LawSource
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaws
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.expr.ExprEnv
import com.astarworks.astera.core.domain.skillengine.expr.ExprEvalException
import com.astarworks.astera.core.application.skillengine.load.ops.OccurrenceBake
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
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
import com.astarworks.astera.core.domain.sprite.SpriteGlyphs
import com.astarworks.astera.core.domain.sprite.SpriteId
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import kotlin.math.abs

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
    /** `part` of an item display: a part of `content/frames/<skill item>.yaml` whose axes (`long`, `edge`, `face`) the display's rotations read; null = the item's held part. */
    public val part: String?,
    public val textStyle: TextStyle?,
    public val spriteSize: Pair<Value<Double>, Value<Double>>?,
    public val spritePivot: String?,
    /** `color` of a text / sprite display (#rrggbb or #aarrggbb); null = the glyph's own colours. */
    public val color: Value<String>?,
    /** `see_through` of a text / sprite display; null on item / block. */
    public val seeThrough: Value<Boolean>?,
    public val flipbook: Flipbook?,
    public val translation: Value<Vec3>,
    public val rotate: Rotation,
    public val scale: Value<Vec3>,
    public val right: Rotation,
    public val pitch: Value<Double>,
    public val billboard: String,
    public val brightness: Pair<Value<Double>, Value<Double>>?,
    public val viewRange: Value<Double>,
    public val glowColor: Value<String>?,
    public val interpolationDelay: Value<Double>,
    public val interpolationDuration: Value<Double>,
    public val teleportDuration: Value<Double>,
    /** The `motion:` list; read per copy by [LawSource.laws] (so a law number may read `i`) and segmented into `Key` rows (and `Set` rows for a fade) by [LawSegmenter] at compile. */
    public val motion: LawSource?,
    public val shaderEffect: String?,
) : OpNode

/** Sprite `flipbook`: [frames] glyphs, one step every [every] ticks; [loop] wraps to frame 0, otherwise the last frame holds. */
public class Flipbook(public val frames: Int, public val every: Int, public val loop: Boolean)

/** The `kind: text` fields (TextDisplay). */
public class TextStyle(
    public val background: Value<String>?,
    public val alignment: String,
    public val lineWidth: Value<Double>,
    public val textOpacity: Value<Double>,
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

    private val MOTION = object : FieldType<LawSource> {
        override val label = "list of motion laws"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): LawSource = LawSource(raw, ctx, path).also { it.probe() }
        override fun schema(): Map<String, Any?> = MotionLaws.jsonSchema()
    }

    private val kindF = Field.required("kind", Types.enumOf("item", "block", "text", "sprite"), "item | block | text | sprite")
    private val anchorF = Field.required("anchor", Types.id, "anchor name; the entity LOCATION is the anchor's world origin plus offset (2.3)")
    private val offsetF = Field.optional("offset", Types.nullable(FrameVecs.type), null, "{forward, up, left} in the anchor's axes, folded into the entity location; absent = none")

    // item
    private val itemF = Field.optional("item", Types.nullable(Types.idValue), null, "kind item: item_model id (main_hand = the held item's model, resolved at cast start); under times a per-index list or '= CEL' over i")
    private val shaderEffectF = Field.optional("shader_effect", Types.nullable(Types.id), null, "kind item: isolated effect id from content/shader-patches/effects; excludes brightness")
    private val partF = Field.optional("part", Types.nullable(Types.id), null, "kind item: weapon part of content/frames/<item>.yaml whose axes (long, edge, face) the rotations read")
    private val contextF = Field.optional("context", Types.enumOf(*CONTEXTS), "fixed", "kind item: ItemDisplayContext, decision default fixed")

    // block
    private val blockF = Field.optional("block", Types.nullable(Types.idValue), null, "kind block: block id, e.g. minecraft:white_concrete; under times a per-index list or '= CEL' over i")

    // text
    private val textF = Field.optional("text", Types.nullable(Types.text), null, "kind text: the text")
    private val backgroundF = Field.optional("background", Types.nullable(Types.text), null, "kind text: #aarrggbb; absent = vanilla default background")
    private val alignmentF = Field.optional("alignment", Types.enumOf("center", "left", "right"), "center", "kind text: vanilla default center")
    private val lineWidthF = Field.optional("line_width", Types.number, Value.Literal(200.0), "kind text: vanilla default 200")
    private val textOpacityF = Field.optional("text_opacity", Types.number, Value.Literal(255.0), "kind text: 0..255, vanilla default 255 (opaque)")
    private val seeThroughF = Field.optional("see_through", Types.bool, Value.Literal(false), "kind text | sprite: vanilla default false")
    private val shadowF = Field.optional("shadow", Types.bool, Value.Literal(false), "kind text: vanilla default false")

    // sprite
    private val spriteF = Field.optional("sprite", Types.nullable(Types.idValue), null, "kind sprite: content/sprites id; under times a per-index list or '= CEL' over i")
    private val sizeF = Field.optional("size", Types.nullable(SIZE), null, "kind sprite: [w, h] in BLOCKS (2.1)")
    private val pivotF = Field.optional("pivot", Types.enumOf("center"), "center", "kind sprite: the glyph is centred on the entity by the sprite font (ADR-0239), so center is the only pivot")
    private val colorF = Field.optional("color", Types.nullable(Types.text), null, "kind text | sprite: colour #rrggbb or #aarrggbb; absent = the glyph's own colours")
    private val flipFrames = Field.required("frames", Types.number, "number of glyph frames of the sprite")
    private val flipEvery = Field.required("every", Types.number, "ticks per frame")
    private val flipLoop = Field.optional("loop", Types.bool, Value.Literal(false), "wrap to frame 0 after the last frame; false holds the last frame")
    private val flipbookFields = Fields.of("flipbook", flipFrames, flipEvery, flipLoop)
    private val flipbookF = Field.optional("flipbook", Types.nullable(Types.nested(flipbookFields)), null, "kind sprite: steps the glyph frame, one text write per step (lowered at compile)")

    /** `[x, y, z]` / `${param}` / `= expr` (vanilla scale), or the map `{left, up, forward}` (x, y, z), the one spelling of a triple that `times: 3` does not read as three per-copy scales (B1). */
    private val scaleType: FieldType<Value<Vec3>> = object : FieldType<Value<Vec3>> {
        override val label = "[x, y, z], {left, up, forward}, \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Value<Vec3> {
            if (raw !is Map<*, *>) return Types.worldVec3.decode(raw, ctx, path)
            val keys = listOf("left", "up", "forward")
            raw.keys.firstOrNull { it !in keys }?.let { ctx.fail("$path.$it", "unknown scale axis '$it' (expected left, up, forward; all three are written)") }
            val axes = keys.map { k ->
                if (k !in raw) ctx.fail("$path.$k", "a scale written per axis needs all of left, up, forward (missing '$k')")
                Types.number.decode(raw[k], ctx, "$path.$k")
            }
            val lits = axes.filterIsInstance<Value.Literal<Double>>()
            if (lits.size == 3) return Value.Literal(Vec3(lits[0].value, lits[1].value, lits[2].value))
            return Value.Expr("vec(${axes.joinToString(", ") { sourceOf(it) }})")
        }
        override fun schema(): Map<String, Any?> = Types.worldVec3.schema()
    }

    // Transformation(translation, left_rotation, scale, right_rotation)
    private val translationF = Field.optional("translation", Types.worldVec3, Value.Literal(Vec3(0.0, 0.0, 0.0)), "vanilla translation, entity-local axes (never rotated by left_rotation, 2.3)")
    private val rotateF = Field.optional("rotate", Rotations.type, Rotation.Turns(emptyList()), "left_rotation: named-axis list or quaternion; absent = identity")
    private val scaleF = Field.optional("scale", scaleType, Value.Literal(Vec3(1.0, 1.0, 1.0)), "vanilla scale; sprites use size")
    private val rightF = Field.optional("right", Rotations.type, Rotation.Whole(Value.Literal(Quaternion.IDENTITY)), "right_rotation, quaternion [x, y, z, w]; absent = identity")
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
        "item" to listOf(itemF, partF, contextF, shaderEffectF),
        "block" to listOf(blockF),
        "text" to listOf(textF, backgroundF, alignmentF, lineWidthF, textOpacityF, shadowF),
        "sprite" to listOf(spriteF, sizeF, pivotF, flipbookF),
    )

    override val fields: Fields = Fields.of(
        "display", kindF, anchorF, offsetF,
        itemF, partF, contextF, shaderEffectF, blockF,
        textF, backgroundF, alignmentF, lineWidthF, textOpacityF, seeThroughF, shadowF,
        spriteF, sizeF, pivotF, flipbookF, colorF,
        translationF, rotateF, scaleF, rightF, pitchF, billboardF, brightnessF, viewRangeF, glowColorF,
        interpDelayF, interpDurationF, teleportDurationF, motionF,
    ).rejecting(
        "yaw",
        "entity yaw is never written in a skill file (the display is spawned at yaw 0 and all orientation lives in 'rotate'); " +
            "write a turn as {axis: up, deg: N} (vanilla yaw = -deg about up)",
    )

    // ------------------------------------------------------------------------------------------------ decode

    /**
     * Why a law may not run on a billboarded display, or null. The client faces the display toward the camera and the transformation applies
     * after that, so only a roll about the local Z (forward, view) axis survives as a stable screen-plane turn; any other turn fights the facing.
     */
    private fun billboardLawError(law: MotionLaw): String? {
        fun rollOnly(v: Vec3) = kotlin.math.abs(v.x) < 1e-9 && kotlin.math.abs(v.y) < 1e-9
        fun axisText(v: Vec3) = "(${v.x}, ${v.y}, ${v.z})"
        return when (law) {
            is RateMotion -> (law.rate as? SpinRate)?.let { r ->
                when {
                    !rollOnly(r.axis) -> "spin about axis ${axisText(r.axis)}"
                    !rollOnly(r.start) -> "spin starting from rotation ${axisText(r.start)}"
                    else -> null
                }
            }
            is AlignMotion -> "align (axis '${law.axisName}')"
            is OrbitMotion -> if (law.face != "keep") "orbit with face '${law.face}'" else null
            is KeysMotion -> law.keys.firstNotNullOfOrNull { k -> k.pose?.takeIf { !rollOnly(it) }?.let { "keys pose about ${axisText(it)} at t=${k.tick}" } }
            else -> null
        }
    }

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
            if (kind != DisplayKind.TEXT && kind != DisplayKind.SPRITE) {
                for (f in listOf(seeThroughF, colorF)) {
                    if (values.isPresent(f)) ctx.fail("$p.${f.name}", "'${f.name}' belongs to kind text or sprite, not to kind ${values[kindF]}")
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
            if (billboard != "fixed") {
                // ADR-0303 15.7: the client's billboard is a sprite / text facing, never a third pose mode
                if (kind != DisplayKind.SPRITE && kind != DisplayKind.TEXT) {
                    ctx.fail("$p.billboard", "billboard '$billboard' needs kind sprite or text: a billboarded ${values[kindF]} display is a facing 3D object, which is a third pose mode")
                }
            }
            val subject: Value<String> = when (kind) {
                DisplayKind.ITEM -> values[itemF]?.let { foldText(it, ctx) } ?: ctx.abortMissing(p, "item")
                DisplayKind.BLOCK -> values[blockF]?.let { foldText(it, ctx) } ?: ctx.abortMissing(p, "block")
                DisplayKind.TEXT -> values[textF]?.let { foldText(it, ctx) } ?: ctx.abortMissing(p, "text")
                DisplayKind.SPRITE -> values[spriteF]?.let { foldText(it, ctx) } ?: ctx.abortMissing(p, "sprite")
            }
            val shaderEffect = values[shaderEffectF]
            if (shaderEffect != null) {
                val item = (subject as? Value.Literal)?.value ?: ctx.fail("$p.shader_effect", "shader_effect needs a literal item id (it is checked against main_hand / minecraft: ids at load)")
                if (!Regex("[a-z][a-z0-9-]*").matches(shaderEffect)) ctx.fail("$p.shader_effect", "invalid shader effect id")
                if (item == "main_hand" || item.startsWith("minecraft:") || values.isPresent(brightnessF)) {
                    ctx.fail("$p.shader_effect", "shader_effect requires an Astera pack item and excludes brightness")
                }
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
            val color = values[colorF]?.let { foldText(it, ctx).also { c -> checkHex(c, "$p.color", ctx, allowAlpha = true) } }
            val seeThrough = if (kind == DisplayKind.TEXT || kind == DisplayKind.SPRITE) foldBool(values[seeThroughF], ctx) else null
            val flipbook = values[flipbookF]?.let { fb ->
                val frames = flipbookInt(fb[flipFrames], "$p.flipbook.frames", ctx, 1, SpriteGlyphs.MAX_FRAMES)
                val every = flipbookInt(fb[flipEvery], "$p.flipbook.every", ctx, 1, null)
                val loop = (foldBool(fb[flipLoop], ctx) as? Value.Literal)?.value ?: ctx.fail("$p.flipbook.loop", "loop must be a literal true / false")
                Flipbook(frames, every, loop)
            }
            val glow = values[glowColorF]?.let { foldText(it, ctx).also { c -> checkHex(c, "$p.glow_color", ctx, allowAlpha = false) } }
            val background = values[backgroundF]?.let { foldText(it, ctx).also { c -> checkHex(c, "$p.background", ctx, allowAlpha = true) } }

            val style = if (kind == DisplayKind.TEXT) {
                TextStyle(
                    background, values[alignmentF],
                    wholeNum(values[lineWidthF], "$p.line_width", ctx, 1.0, null),
                    wholeNum(values[textOpacityF], "$p.text_opacity", ctx, 0.0, 255.0),
                    foldBool(values[shadowF], ctx),
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
                subject, values[contextF], values[partF], style, size, if (kind == DisplayKind.SPRITE) values[pivotF] else null, color, seeThrough, flipbook,
                values[translationF], values[rotateF], values[scaleF], values[rightF],
                foldNum(values[pitchF], ctx), billboard, brightness, viewRange, glow,
                wholeNum(values[interpDelayF], "$p.interpolation_delay", ctx, 0.0, null),
                wholeNum(values[interpDurationF], "$p.interpolation_duration", ctx, 0.0, null),
                wholeNum(values[teleportDurationF], "$p.teleport_duration", ctx, 0.0, null),
                values[motionF], shaderEffect,
            )
        }

    // ------------------------------------------------------------------------------------------------ lower

    override fun lower(op: DisplayOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val t = op.timing ?: return Result.Failure(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "display lost its timing block"))
        val instance = instanceOf(lc)
        val src = lc.src
        return Grammar.guard {
            val gc = GrammarContext(lc.skillId)
            if (op.billboard != "fixed") {
                // every copy of a family is lowered with its own occurrence, so the laws are checked per copy index
                op.motion?.laws(lc.occurrence)?.firstNotNullOfOrNull { l -> billboardLawError(l)?.let { l to it } }?.let { (l, why) ->
                    gc.fail("${l.path}", "billboard '${op.billboard}' allows only a roll about the local Z axis (axis forward / back): $why")
                }
            }
            AnchorNames.bareFamilyError(lc.anchors, op.anchor, "display '${op.id.value.substringAfterLast('/')}'")?.let { gc.fail("${src.value}.anchor", it) }
            val itemAxes = if (op.kind != DisplayKind.ITEM) null else if (op.part != null) {
                val skillItem = lc.skills.skill(lc.skillId)?.item
                    ?: gc.fail("${src.value}.part", "part '${op.part}' needs the skill's item to find content/frames/<item>.yaml, but this skill has no item")
                (lc.frames.item(skillItem)?.partFor(op.part)
                    ?: gc.fail("${src.value}.part", "content/frames/$skillItem.yaml has no part '${op.part}'")).axes
            } else lc.frames.item(literalSubject(op, lc, "item"))?.partFor(null)?.axes
            val axes = AxisResolver { n -> LocalAxes.axis(n) ?: if (n == "face") FACE else itemAxes?.get(n) }
            for ((r, f) in listOf(op.rotate to "rotate", op.right to "right")) {
                if (r is Rotation.Turns) r.turns.forEachIndexed { i, turn ->
                    if (turn.frame != null) gc.fail("${src.value}.$f[$i].axis", "a frame-qualified axis ('${turn.frame}.${turn.axis}') is not supported on a display rotation; use a plain or item axis")
                }
            }
            // an angle that is a `= CEL` of constants and this copy's i folds here, so the base rotation is a literal a motion composes onto
            val foldedRotate = (op.rotate as? Rotation.Turns)?.let { r ->
                Rotation.Turns(
                    r.turns.map { turn ->
                        val deg = turn.deg
                        val num = (deg as? Value.Expr)?.let { OccurrenceBake.fold(lc, it.source, ExprType.NUM) as? ExprValue.Num }
                        if (num != null) turn.copy(deg = Value.Literal(num.value)) else turn
                    },
                )
            } ?: op.rotate
            fun foldAxis(v: Value<Double>): Value<Double> =
                (v as? Value.Expr)?.let { (OccurrenceBake.fold(lc, it.source, ExprType.NUM) as? ExprValue.Num)?.let { n -> Value.Literal(n.value) } } ?: v
            val foldedOffset = (op.offset as? FrameVec.Parts)?.let { FrameVec.Parts(foldAxis(it.forward), foldAxis(it.up), foldAxis(it.left)) } ?: op.offset
            val left = foldedRotate.toValue(gc, "${src.value}.rotate", axes)
            val right = op.right.toValue(gc, "${src.value}.right", axes)

            val end = endTick(op, t, lc)
            val sprite = if (op.kind == DisplayKind.SPRITE) spriteScale(op, lc, end) else null
            val pose = Pose(
                position = foldedOffset?.toLocal()?.let { vecCell(it, lc) } ?: Val.Known(Vector3d(0.0, 0.0, 0.0)),
                rotation = quatCell(left, lc),
                scale = sprite?.scale ?: vecCell(op.scale, lc),
            )
            // a rider of a MOVING frame skips it (its keys are absolute, so riding the frame's rec would count the frame's laws twice): it spawns on the chain's parent with the chain's base folded in
            val inChain = lc.anchors.chain(op.anchor)
            val spawnPose = if (inChain != null) PoseCompose.combine(inChain.base, pose) { gc.fail("${src.value}.offset", it) } else pose
            val spawnParent = if (inChain != null) inChain.parent else anchorInstance(op, lc)
            val spawnHost = inChain?.parentName ?: AnchorNames.host(lc.anchors, op.anchor)
            val spawn = Row(0, instance, Action.Spawn(spawnKind(op.kind), spawnParent, spawnHost, spawnPose, look(op, right, sprite, lc)), src)

            val rows = ArrayList<Row>()
            rows += spawn
            val subTables = ArrayList<SubTable>()

            if (end != null) rows += Row(end, instance, Action.End(instance), src)
            t.until?.let { ev ->
                // `until` is a mask (2.0): the event's sub-table ends this instance the first time it fires.
                val hi = lc.clockOffset + (end ?: lc.config.maxLifeTicks)
                subTables += SubTable(
                    EventPath(ev), lc.state, TickRange(lc.clockOffset, maxOf(lc.clockOffset, hi)), 1,
                    listOf(Row(0, instance, Action.End(instance), src)), emptyList(), mask = true,
                )
            }

            var writes = emptyMap<InstanceId, com.astarworks.astera.core.domain.skillengine.table.WriteStats>()
            val flipRows = op.flipbook?.let { flipbookRows(op, it, instance, end, lc) }.orEmpty()
            rows += flipRows
            // The anchor's own laws ride first: left_rotation = anchor.rot(t) * rider (item 16).
            val laws = MotionLaws.rider(inChain?.laws ?: lc.anchors.laws(op.anchor), op.motion?.laws(lc.occurrence).orEmpty() + (sprite?.sizeLaws ?: emptyList()), "skill '${lc.skillId}' rider '${op.id.value.substringAfterLast('/')}'") { gc.fail("${src.value}.motion", it) }
            if (laws.isNotEmpty()) {
                val moved = LawSegmenter.segments(instance, laws, lc, FADE_FIELD)
                when (moved) {
                    is Result.Failure -> throw DecodeAbort(moved.error)
                    is Result.Success -> {
                        // a law (or the anchor's law) outlasting this display's `for:` writes nothing once the display has ended
                        val alive = if (end != null) moved.value.rows.filter { it.tick < end } else moved.value.rows
                        // every Key is a whole pose: carry the op's own offset / rotate / scale or the first key overwrites them
                        val own = PoseCompose.composeBase(alive, pose.position, pose.rotation, pose.scale) { gc.fail("${src.value}.motion", it) }
                        val inLife = if (inChain != null) PoseCompose.composeBase(own, inChain.base.position, inChain.base.rotation, inChain.base.scale) { gc.fail("${src.value}.motion", it) } else own
                        rows += inLife
                        subTables += moved.value.subTables
                        writes = if (alive.size == moved.value.rows.size) moved.value.writes else {
                            val perTick = inLife.groupingBy { it.tick }.eachCount()
                            mapOf(instance to com.astarworks.astera.core.domain.skillengine.table.WriteStats(inLife.size, perTick.values.maxOrNull() ?: 0))
                        }
                    }
                }
            }
            if (flipRows.isNotEmpty()) {
                val prev = writes[instance]
                val perTick = flipRows.groupingBy { it.tick }.eachCount()
                writes = writes + (instance to com.astarworks.astera.core.domain.skillengine.table.WriteStats(
                    (prev?.segments ?: 0) + flipRows.size, maxOf(prev?.maxWritesPerTick ?: 0, perTick.values.max()),
                ))
            }
            Lowered(rows.sortedBy { it.tick }, subTables, listOf(instance), emptyList(), writes)
        }
    }

    /** A fade law writes the vanilla TextDisplay opacity field (text and sprite displays; item / block use `grow`). */
    private const val FADE_FIELD = "text_opacity"

    /**
     * `flipbook` (R23): frame 0 is the spawn glyph; every [Flipbook.every] ticks after it one `Set text=<glyph>` row steps the frame, to the
     * display's end (the cast's `maxLifeTicks` when the display has no `for`). `loop` wraps, otherwise the last frame holds with no more rows.
     */
    private fun flipbookRows(op: DisplayOp, fb: Flipbook, instance: InstanceId, end: Int?, lc: LowerContext): List<Row> {
        val id = literalSubject(op, lc, "sprite")
        val known = lc.sprites.frames(id) ?: throw DecodeAbort(SkillDecodeError.UndefinedReference(lc.skillId, lc.src.value, id))
        if (fb.frames > known) {
            throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, "${lc.src.value}.flipbook.frames", "sprite '$id' has $known frame(s), flipbook asks for ${fb.frames}"))
        }
        val last = end ?: lc.config.maxLifeTicks
        val rows = ArrayList<Row>()
        var step = 1
        while (step * fb.every < last) {
            if (!fb.loop && step >= fb.frames) break
            val frame = step % fb.frames
            rows += Row(step * fb.every, instance, Action.Set("text", Val.Known(SpriteGlyphs.text(SpriteId(id), frame))), lc.src)
            step++
        }
        return rows
    }

    /** Sprite sizing (item 10): natural quad = px * 0.025 blocks; `scale` = size / natural, so the gateway computes nothing. */
    private class SpriteScale(val scale: Val<Vector3d>, val sizeLaws: List<MotionLaw> = emptyList(), val spawnSize: Val<Vector3d>? = null)

    private fun spriteScale(op: DisplayOp, lc: LowerContext, end: Int?): SpriteScale {
        val id = literalSubject(op, lc, "sprite")
        val natural = lc.sprites.natural(id)
            ?: throw DecodeAbort(SkillDecodeError.UndefinedReference(lc.skillId, lc.src.value, id))
        val nw = natural.w * SPRITE_BLOCKS_PER_PX
        val nh = natural.h * SPRITE_BLOCKS_PER_PX
        val (w, h) = op.spriteSize!!
        if (isTimeClass(w) || isTimeClass(h)) return timeSizedScale(op, lc, end, w, h, nw, nh)
        val scale: Val<Vector3d> =
            if (w is Value.Literal && h is Value.Literal) {
                Val.Known(Vector3d(w.value / nw, h.value / nh, 1.0))
            } else {
                Val.Live("vec(${sourceOf(w)} / $nw, ${sourceOf(h)} / $nh, 1.0)", null)
            }
        return SpriteScale(scale)
    }

    /** `size` component that reads `t` / `lt` (class T, ADR-0303 3.5) and nothing live. */
    private fun isTimeClass(v: Value<Double>): Boolean =
        v is Value.Expr && ExprClassifier.analyse(v.source).let { it.cls == ExprClass.T }

    /**
     * Class T `size` (ADR-0303 3.5, 3.4): the expression is sampled on the tick grid over the display's life, fitted to the fewest linear
     * segments whose chord error stays under `segmentErrorBlocks` (in blocks of width / height), and written as a `keys` scale law that the
     * [LawSegmenter] turns into `Key` rows (one write per fitted segment). The spawn scale is the size at tick 0; the keys are multipliers of it.
     */
    private fun timeSizedScale(op: DisplayOp, lc: LowerContext, end: Int?, w: Value<Double>, h: Value<Double>, nw: Double, nh: Double): SpriteScale {
        val path = "${lc.src.value}.size"
        val last = end ?: throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, path, "a size that follows t needs a finite lifetime: give the display a 'for' (ADR-0303 3.5)"))
        val spec = V3Bindings.spec(lc.occurrence)
        fun sampler(v: Value<Double>, axis: String): (Int) -> Double = when (v) {
            is Value.Literal -> { _ -> v.value }
            is Value.ParamRef -> throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, "$path.$axis", "\${${v.name}} was not substituted before lowering"))
            is Value.Expr -> {
                val compiled = when (val c = lc.exprs.compile(v.source, spec, ExprType.NUM)) {
                    is Result.Success -> c.value
                    is Result.Failure -> throw DecodeAbort(SkillDecodeError.BadExpr(lc.skillId, "$path.$axis", c.error))
                }
                val a = ExprClassifier.analyse(v.source) { n -> lc.anchors.resolve(n)?.basis }
                if (a.usesLive || a.usesRand) {
                    throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, "$path.$axis", "a size that follows t cannot also be live or random: it is fitted at compile time"))
                }
                val sampleAt: (Int) -> Double = { k ->
                    val env = ExprEnv(V3Bindings.values(lc.occurrence, instanceTick = k, lawTick = k), NoFrames)
                    val r = try {
                        compiled.eval(env)
                    } catch (e: ExprEvalException) {
                        throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, "$path.$axis", "${e.message} (at t=$k)"))
                    }
                    (r as? ExprValue.Num)?.value ?: throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, "$path.$axis", "is not a number"))
                }
                sampleAt
            }
        }
        val sw = sampler(w, "[0]")
        val sh = sampler(h, "[1]")
        val ws = DoubleArray(last + 1) { sw(it) }
        val hs = DoubleArray(last + 1) { sh(it) }
        for (k in 0..last) {
            if (!(ws[k] > 0.0) || !(hs[k] > 0.0) || ws[k].isInfinite() || hs[k].isInfinite()) {
                throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, path, "size must stay finite and > 0 on every tick (t=$k gave ${ws[k]} x ${hs[k]})"))
            }
        }
        val budget = lc.config.segmentErrorBlocks
        val cuts = arrayListOf(0)
        while (cuts.last() < last) {
            val s = cuts.last()
            var e = s + 1
            while (e + 1 <= last && chordFits(ws, hs, s, e + 1, budget)) e++
            cuts += e
        }
        val keys = cuts.map { k -> LawKey(k, null, null, Vec3(ws[k] / ws[0], hs[k] / hs[0], 1.0), null, null) }
        val law = KeysMotion(keys, 0, "${lc.src.value}.size")
        return SpriteScale(Val.Known(Vector3d(ws[0] / nw, hs[0] / nh, 1.0)), listOf(law), Val.Known(Vector3d(ws[0], hs[0], 1.0)))
    }

    /** The straight line from sample [s] to sample [e] stays within [budget] blocks of both the width and the height samples between them. */
    private fun chordFits(ws: DoubleArray, hs: DoubleArray, s: Int, e: Int, budget: Double): Boolean {
        for (k in s + 1 until e) {
            val f = (k - s).toDouble() / (e - s)
            if (abs(ws[s] + (ws[e] - ws[s]) * f - ws[k]) > budget) return false
            if (abs(hs[s] + (hs[e] - hs[s]) * f - hs[k]) > budget) return false
        }
        return true
    }

    private object NoFrames : FrameLookup {
        override fun worldPose(frame: String, point: String?): Frame? = null
        override fun velocity(frame: String, point: String?): Vec3 = Vec3.ZERO
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
    private fun look(op: DisplayOp, right: Value<Quaternion>, sprite: SpriteScale?, lc: LowerContext): Look {
        val f = LinkedHashMap<String, Val<Any>>()
        when (op.kind) {
            DisplayKind.ITEM -> {
                val shown = literalSubject(op, lc, "item")
                // a main-hand mesh draws the model of the item the cast is bound to (CastRequest.heldItem falls back to it): without one an admin / bot cast has nothing to draw
                if (shown == MeshModels.MAIN_HAND && lc.item?.takeIf { it != "any" } == null) {
                    throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "display item main_hand needs a concrete skill item: (not any) so a cast without a triggering item still knows which model to draw"))
                }
                f["item"] = Val.Known(shown)
                op.part?.let { f["part"] = Val.Known(it) }
                f["context"] = Val.Known(op.context)
                op.shaderEffect?.let { f["shader_effect"] = Val.Known(it) }
            }
            DisplayKind.BLOCK -> f["block"] = idCell(op.subject, lc)
            DisplayKind.TEXT -> {
                val s = op.textStyle!!
                f["text"] = cell(op.subject)
                s.background?.let { f["background"] = cell(it) }
                f["alignment"] = Val.Known(s.alignment)
                f["line_width"] = cell(s.lineWidth)
                f["text_opacity"] = cell(s.textOpacity)
                f["shadow"] = cell(s.shadow)
                f["see_through"] = cell(op.seeThrough!!)
                op.color?.let { f["color"] = cell(it) }
            }
            DisplayKind.SPRITE -> {
                val (w, h) = op.spriteSize!!
                f["sprite"] = Val.Known(literalSubject(op, lc, "sprite"))
                f["size"] = sprite?.spawnSize ?: vecCell(w, h)
                f["pivot"] = Val.Known(op.spritePivot!!)
                f["scale"] = sprite!!.scale
                f["see_through"] = cell(op.seeThrough!!)
                op.color?.let { f["color"] = cell(it) }
            }
        }
        f["translation"] = vecCell(op.translation, lc)
        f["right_rotation"] = quatCell(right, lc)
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

    /** The item / sprite id of [op] for this copy: a literal, or a `= CEL` over `i` / `n` folded here; anything that stays live is a named load error (the id picks the model axes / sprite size at lowering). */
    private fun literalSubject(op: DisplayOp, lc: LowerContext, what: String): String = when (val s = op.subject) {
        is Value.Literal -> s.value
        is Value.Expr -> (OccurrenceBake.fold(lc, s.source, ExprType.STR) as? ExprValue.Str)?.value
            ?: throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "display $what '= ${s.source}' must fold at compile time (only i, n and literals); a live value cannot pick the $what"))
        is Value.ParamRef -> throw DecodeAbort(SkillDecodeError.Invalid(lc.skillId, lc.src.value, "display $what param '${s.name}' is not a known param at load"))
    }

    /** A string id cell: a `= CEL` over `i` / `n` is folded for this copy (class S) so the table holds the constant; anything live stays baked. */
    private fun idCell(v: Value<String>, lc: LowerContext): Val<String> {
        if (v !is Value.Expr) return cell(v)
        (OccurrenceBake.fold(lc, v.source, ExprType.STR) as? ExprValue.Str)?.let { return Val.Known(it.value) }
        return Val.Live("= ${OccurrenceBake.bake(v.source, lc.occurrence)}", null)
    }

    private fun <T> cell(v: Value<T>): Val<T> = when (v) {
        is Value.Literal -> Val.Known(v.value)
        is Value.ParamRef -> Val.Live("\${${v.name}}", null)
        is Value.Expr -> Val.Live("= ${v.source}", null)
    }

    /** A `= CEL` of constants and this copy's i folds to a literal here, so a motion can compose onto it; a live one stays a cell. */
    private fun vecCell(v: Value<Vec3>, lc: LowerContext): Val<Vector3d> = when (v) {
        is Value.Literal -> Val.Known(Vector3d(v.value.x, v.value.y, v.value.z))
        is Value.Expr -> (OccurrenceBake.fold(lc, v.source, ExprType.VEC3) as? ExprValue.Vec3)?.let { Val.Known(Vector3d(it.value.x, it.value.y, it.value.z)) }
            ?: cell(v).let { c -> c as Val<Vector3d> }
        else -> cell(v).let { c -> c as Val<Vector3d> }
    }

    /** A sprite size [w, h] as the vector (w, h, 1). */
    private fun vecCell(w: Value<Double>, h: Value<Double>): Val<Vector3d> =
        if (w is Value.Literal && h is Value.Literal) {
            Val.Known(Vector3d(w.value, h.value, 1.0))
        } else {
            Val.Live("vec(${sourceOf(w)}, ${sourceOf(h)}, 1.0)", null)
        }

    private fun quatCell(q: Value<Quaternion>, lc: LowerContext): Val<Quaterniond> = when (q) {
        is Value.Literal -> Val.Known(Quaterniond(q.value.x, q.value.y, q.value.z, q.value.w))
        is Value.Expr -> (OccurrenceBake.fold(lc, q.source, ExprType.QUAT) as? ExprValue.Quat)?.let { Val.Known(Quaterniond(it.value.x, it.value.y, it.value.z, it.value.w)) }
            ?: cell(q).let { c -> c as Val<Quaterniond> }
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
        detach = t.detach,
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

    private fun flipbookInt(v: Value<Double>, path: String, ctx: GrammarContext, lo: Int, hi: Int?): Int {
        val lit = (foldNum(v, ctx) as? Value.Literal)?.value ?: ctx.fail(path, "must be a literal whole number")
        if (lit != Math.floor(lit) || lit < lo || (hi != null && lit > hi)) {
            ctx.fail(path, "must be a whole number in $lo.." + (hi?.toString() ?: "") + " (was $lit)")
        }
        return lit.toInt()
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

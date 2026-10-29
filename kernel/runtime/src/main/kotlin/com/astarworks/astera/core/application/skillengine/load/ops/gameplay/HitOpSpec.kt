package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.HandlerLowering
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Timing
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingKind
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpec
import com.astarworks.astera.core.application.skillengine.load.spec.WhenRef
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.HitDamage
import com.astarworks.astera.core.domain.skillengine.table.HitShape
import com.astarworks.astera.core.domain.skillengine.table.HitTargets
import com.astarworks.astera.core.domain.skillengine.table.HitWindow
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.Vec
import com.astarworks.astera.core.domain.skillengine.table.WriteStats

/** The two events a `hit` raises (R7): one `hit` per victim, one `miss` when the whole op found nobody. */
public enum class HitEvent(public val key: String) {
    HIT("hit"), MISS("miss");

    public companion object {
        public fun ofKey(key: String): HitEvent? = entries.firstOrNull { it.key == key }
    }
}

/** The one volume a `hit` declares (ADR-0303 10.1b). */
public sealed interface HitShapeDecl {
    public data class Sphere(val radius: Value<Double>) : HitShapeDecl
    public data class Capsule(val radius: Value<Double>, val from: String, val to: String) : HitShapeDecl

    /** [deg] is the full opening; [yaw] = `forward: yaw` (flattened to the horizontal), false = `forward: look`. */
    public data class Sector(val deg: Value<Double>, val range: Value<Double>, val height: Value<Double>, val yaw: Boolean) : HitShapeDecl
}

/**
 * Decoded `hit` op (ADR-0303 10.1b, R7/R8/R12/R15/R21/R24): detection and damage in one op. [damage] absent = detection only.
 * The `on:` handler lists are ops of any kind, so they are kept raw in [rawOn] and attached by the document's `OpListDecoder` with [withHandlers].
 */
public class HitOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val anchor: String,
    public val shape: HitShapeDecl,
    public val targets: String,
    /** null = every victim the shape found (`all`, or omitted: see [GameplayOps.MAX_COUNT_OMITTED_IS_LOAD_ERROR]). */
    public val maxCount: Int?,
    public val window: HitWindow?,
    public val ignoreInvulnerability: Boolean,
    public val damage: List<Pair<DamageAttribute, Value<Double>>>,
    public val rawOn: Map<HitEvent, List<Any?>>,
    public val handlers: Map<HitEvent, List<OpNode>> = emptyMap(),
    public val lowerHandlers: HandlerLowering? = null,
) : OpNode {
    public fun withHandlers(decoded: Map<HitEvent, List<OpNode>>, lowering: HandlerLowering): HitOp =
        HitOp(id, src, whenExpr, timing, anchor, shape, targets, maxCount, window, ignoreInvulnerability, damage, rawOn, decoded, lowering)
}

public object HitOpSpec : OpSpec<HitOp> {
    override val name: String = "hit"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.POINT

    private val rawHandlers: FieldType<List<Any?>?> = object : FieldType<List<Any?>?> {
        override val label = "non-empty list of ops"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<Any?> {
            val l = raw as? List<*> ?: ctx.mismatch(path, label, raw)
            if (l.isEmpty()) ctx.fail(path, "an empty handler list; leave the event out")
            return l
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array", "minItems" to 1, "items" to linkedMapOf("type" to "object"))
    }

    /** `once` or `{every: N}` (whole ticks >= 1). */
    private val windowType: FieldType<HitWindow> = object : FieldType<HitWindow> {
        override val label = "'once' or {every: ticks}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): HitWindow {
            if (raw == "once") return HitWindow.Once
            if (raw is Map<*, *>) {
                val every = raw["every"]
                if (raw.size != 1 || every !is Number) ctx.mismatch(path, label, raw)
                if (every.toDouble() % 1.0 != 0.0 || every.toInt() < 1) ctx.fail(path, "window.every must be a whole number of ticks >= 1, found $every")
                return HitWindow.Every(every.toInt())
            }
            ctx.mismatch(path, label, raw)
        }
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "oneOf" to listOf(
                linkedMapOf("const" to "once"),
                linkedMapOf("type" to "object", "required" to listOf("every"), "properties" to linkedMapOf("every" to linkedMapOf("type" to "integer", "minimum" to 1))),
            ),
        )
    }

    private val sectorDeg = Field.required("deg", Types.number, "the FULL opening in degrees (half to each side of the forward)")
    private val sectorRange = Field.required("range", Types.number, "radius in blocks")
    private val sectorHeight = Field.required("height", Types.number, "total height in blocks (hits within +-height/2 of the origin)")
    private val sectorForward = Field.required("forward", Types.enumOf("look", "yaw"), "look = the anchor's forward; yaw = its forward flattened to the horizontal")
    private val sectorFields: Fields = Fields.of("hit.shape.sector", sectorDeg, sectorRange, sectorHeight, sectorForward)

    private val shapeSphere = Field.optional("sphere", Types.nullable(Types.number), null, "sphere radius (centre = the anchor point)")
    private val shapeCapsule = Field.optional("capsule", Types.nullable(Types.number), null, "capsule radius, from/to name points of the anchor frame")
    private val shapeFrom = Field.optional("from", Types.nullable(Types.id), null, "capsule start point")
    private val shapeTo = Field.optional("to", Types.nullable(Types.id), null, "capsule end point")
    private val shapeSector = Field.optional("sector", Types.nullable(Types.nested(sectorFields)), null, "wedge of a sphere: deg, range, height, forward")
    private val shapeFields: Fields = Fields.of("hit.shape", shapeSphere, shapeCapsule, shapeFrom, shapeTo, shapeSector)

    private val damageFields: List<Pair<DamageAttribute, Field<Value<Double>?>>> = DamageAttribute.entries.map {
        it to Field.optional(it.name.lowercase(), Types.nullable(Types.number), null, "${it.name.lowercase()} damage per victim; may read event.victim, contact.* and stacks(...)")
    }
    private val damageSpec: Fields = Fields.of("hit.damage", *damageFields.map { it.second }.toTypedArray())

    private val onEventFields: Map<HitEvent, Field<List<Any?>?>> =
        HitEvent.entries.associateWith { Field.optional(it.key, rawHandlers, null, "ops run per victim (hit) / once when nobody was found (miss); event-relative") }
    private val onFields: Fields = Fields.of("hit-on", *onEventFields.values.toTypedArray())

    private val anchorField = Field.required("anchor", Types.id, "frame / point the shape is measured from (caster.body, a frame id, an instance)")
    private val shapeField = Field.required("shape", Types.nested(shapeFields), "exactly one of sphere / capsule / sector")
    private val targetsField = Field.required("targets", Types.enumOf("enemies", "allies", "all"), "which entities the volume may touch (never the caster)")
    private val maxCountField = GameplayOps.maxCountField("keep the nearest N victims (nearest first from the shape origin); `all` = unlimited")
    private val windowField = Field.optional("window", Types.nullable(windowType), null, "re-hit rule; required with `every`: once = a victim at most once, {every: N} = again after N ticks")
    private val ignoreInvulnerabilityField = Field.required("ignore_invulnerability", GameplayOps.flag, "true: the damage also lands on victims inside their invulnerability frames")
    private val damageField = Field.optional("damage", Types.nullable(Types.nested(damageSpec)), null, "damage kinds per victim; absent = detection only (handlers apply the damage)")
    private val onField = Field.optional("on", Types.nullable(Types.nested(onFields)), null, "handlers by event: hit (one per victim), miss (once)")

    override val fields: Fields = Fields.of(
        "hit", anchorField, shapeField, targetsField, maxCountField, windowField, ignoreInvulnerabilityField, damageField, onField,
    )

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<HitOp, SkillDecodeError> =
        Grammar.guard {
            val path = "hit '${id.value}'"
            if (timing?.times != null) ctx.fail("$path.times", "a hit is not a family; repeat it with every / for, or group several hits")
            val window = values[windowField]
            val repeats = timing?.every != null
            if (repeats && window == null) ctx.fail("$path.window", "a repeating hit (every) needs window: once or {every: N}")
            if (!repeats && window is HitWindow.Every) ctx.fail("$path.window", "window: {every: N} re-hits across several firings; this hit fires once (use window: once or leave it out)")

            val shape = values[shapeField]
            val sphere = shape[shapeSphere]
            val capsule = shape[shapeCapsule]
            val sector = shape[shapeSector]
            val given = listOfNotNull(sphere, capsule, sector).size
            if (given != 1) ctx.fail("$path.shape", "needs exactly one of sphere / capsule / sector (found $given)")
            if (capsule == null && (shape[shapeFrom] != null || shape[shapeTo] != null)) ctx.fail("$path.shape", "from / to belong to a capsule")
            val decoded: HitShapeDecl = when {
                sphere != null -> HitShapeDecl.Sphere(sphere)
                capsule != null -> HitShapeDecl.Capsule(
                    capsule,
                    shape[shapeFrom] ?: ctx.fail("$path.shape.from", "capsule needs 'from'"),
                    shape[shapeTo] ?: ctx.fail("$path.shape.to", "capsule needs 'to'"),
                )
                else -> HitShapeDecl.Sector(sector!![sectorDeg], sector[sectorRange], sector[sectorHeight], sector[sectorForward] == "yaw")
            }

            val damage = ArrayList<Pair<DamageAttribute, Value<Double>>>()
            values[damageField]?.let { d ->
                for ((attr, f) in damageFields) d[f]?.let { damage += attr to it }
                if (damage.isEmpty()) ctx.fail("$path.damage", "lists at least one damage kind; leave `damage` out for detection only")
            }

            val raw = LinkedHashMap<HitEvent, List<Any?>>()
            values[onField]?.let { on -> for (e in HitEvent.entries) on[onEventFields.getValue(e)]?.let { raw[e] = it } }

            HitOp(
                id, src, whenExpr, timing, values[anchorField], decoded, values[targetsField], GameplayOps.maxCount(values, maxCountField, ctx, path),
                window, values[ignoreInvulnerabilityField], damage, raw,
            )
        }

    override fun lower(op: HitOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val src = lc.src
        fun bad(message: String): Result.Failure<SkillDecodeError> =
            Result.Failure(SkillDecodeError.Invalid(lc.skillId, src.value, "hit '${op.id.value}': $message"))

        val ref = PointRef.parse(op.anchor)
        val anchorInstance = lc.anchors.resolve(ref.frame)?.instance
        fun point(name: String?): Vec = Val.Live(GameplayOps.point(ref.frame, name), null)
        val origin: Vec = point(ref.point)
        val shape: HitShape = when (val s = op.shape) {
            is HitShapeDecl.Sphere -> HitShape.Sphere(origin, GameplayOps.live(s.radius))
            is HitShapeDecl.Capsule -> HitShape.Capsule(point(s.from), point(s.to), GameplayOps.live(s.radius))
            is HitShapeDecl.Sector -> {
                val forward: Vec = Val.Live((anchorInstance?.text ?: ref.frame) + ".forward", null)
                HitShape.Sector(origin, forward, GameplayOps.live(s.deg), GameplayOps.live(s.range), GameplayOps.live(s.height), s.yaw)
            }
        }
        // the last occurrence of a repeating hit is where `miss` may fire; an `until` that ends the schedule earlier never reaches it (no miss then)
        val index = lc.self.path.last().index
        val finalDetection = op.timing?.every == null || index == null || lc.occurrence.i == lc.occurrence.n - 1
        val hit = Action.Hit(
            shape = shape,
            anchor = anchorInstance,
            anchorName = ref.frame,
            targets = HitTargets(op.targets, op.maxCount),
            window = op.window ?: HitWindow.Once,
            ignoreInvulnerability = op.ignoreInvulnerability,
            finalDetection = finalDetection,
            damage = op.damage.map { (attr, v) -> HitDamage(attr, GameplayOps.live(v)) },
        )
        val rows = listOf(Row(0, lc.self, hit, src))

        if (op.rawOn.isNotEmpty() && op.lowerHandlers == null) return bad("on: handlers were never decoded (OpListDecoder attaches them)")
        // every occurrence of a repeating hit shares one event path and one set of sub-tables (the player keys hit state by the op, not the occurrence)
        val scope = lc.self.path.dropLast(1)
        val opKey = InstanceId(scope + InstanceId.Segment(op.id, null))
        val subTables = ArrayList<SubTable>()
        val instances = ArrayList<InstanceId>()
        val events = ArrayList<EventPath>()
        val writes = LinkedHashMap<InstanceId, WriteStats>()
        for (e in HitEvent.entries) {
            val path = EventPath(opKey.text + "." + e.key)
            events += path
            val nodes = op.handlers[e].orEmpty()
            if (nodes.isEmpty()) continue
            val lowering = op.lowerHandlers ?: return bad("on.${e.key} has handlers but no lowering")
            val prefix = InstanceId(scope + InstanceId.Segment(OpId(op.id.value + "." + e.key), null))
            val hc = LowerContext(
                lc.skillId, lc.state, prefix, lc.occurrence, 0, lc.anchors, lc.frames, lc.parts, lc.skills, lc.item, lc.sprites, lc.conditions, lc.exprs,
                lc.config, lc.seed, SrcPath(src.value + ".on." + e.key),
            )
            val lo = when (val r = lowering.lower(nodes, hc)) {
                is Result.Success -> r.value
                is Result.Failure -> return r
            }
            subTables += lo.subTables
            instances += lo.instances
            events += lo.events
            writes.putAll(lo.writes)
            // This occurrence raises its event on its own firing tick (state clock); the occurrences of a repeating hit share the path, and the
            // assembler unions their windows, so the window is exactly the first..last firing tick, not the whole owning state.
            val window = TickRange(lc.clockOffset, lc.clockOffset)
            subTables += SubTable(path, lc.state, window, Int.MAX_VALUE, lo.rows.sortedBy { it.tick }, emptyList())
        }
        return Result.Success(Lowered(rows, subTables, instances, events.distinct(), writes))
    }
}

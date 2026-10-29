package com.astarworks.astera.core.application.skillengine.load.ops.gameplay

import com.astarworks.astera.core.application.skillengine.compose.AnchorRegistry
import com.astarworks.astera.core.application.skillengine.compose.ExprClass
import com.astarworks.astera.core.application.skillengine.compose.HandlerLowering
import com.astarworks.astera.core.application.skillengine.compose.LowerContext
import com.astarworks.astera.core.application.skillengine.compose.ProjectileKinematics
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldValues
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
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
import com.astarworks.astera.core.application.skillengine.load.ops.presentation.ParticlePlacement
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.OpSection
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.Branch
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Look
import com.astarworks.astera.core.domain.skillengine.table.OpId
import com.astarworks.astera.core.domain.skillengine.table.Pose
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SpawnKind
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.SubTable
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.WriteStats
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d

/** The events a projectile body produces (ADR-0303 3.6). [key] is the yaml key of `on:` and the suffix of the event path (`ball.hit`). */
public enum class ProjectileEvent(public val key: String) {
    HIT("hit"), BOUNCE("bounce"), STICK("stick"), UNSTICK("unstick"), BLOCK("block"), EXPIRE("expire"), END("end");

    public companion object {
        public fun ofKey(key: String): ProjectileEvent? = entries.firstOrNull { it.key == key }
    }
}

/** `speed:` as written: a constant, or the ramp `{from, to, for, ease}` (3.7.1). */
public sealed interface SpeedSpec {
    public data class Constant(val speed: Value<Double>) : SpeedSpec
    public data class Ramp(val from: Value<Double>, val to: Value<Double>, val forTicks: Value<Double>, val ease: String) : SpeedSpec
}

/** `hit:` (3.6). `times` is required exactly when `once` is false. */
public data class ProjectileHit(val targets: String, val once: Value<Boolean>, val times: Value<Double>?, val shape: ProjectileHitShape? = null)

/** R43 `hit.shape`: swept capsules `from -> to` (points of each frame, e.g. `root` / `tip`) of radius [radius] on the rider [frames], instead of the body point's `radius` sphere. */
public data class ProjectileHitShape(val radius: Value<Double>, val from: String, val to: String, val frames: List<String>)

/** `block:` (3.6). Which of [forTicks] / [restitution] / [times] are present is fixed by [mode] at decode. */
public data class ProjectileBlock(val mode: String, val forTicks: Value<Double>?, val restitution: Value<Double>?, val times: Value<Double>?)

/**
 * Decoded `projectile` op (ADR-0303 3.6). The flight length is the timing block's `for` and/or `range`; `times` makes a family (the unroller supplies `i`), while `every`, `until`
 * and `lasts` are not accepted (repeat a projectile with a group). The handler lists of `on:` are ops of any kind, so they are kept raw in [rawOn] and decoded by
 * the document's `OpListDecoder`, which attaches them with [withHandlers]; a projectile that has `on:` handlers but was never given a body fails
 * at lowering.
 */
public class ProjectileOp(
    override val id: OpId,
    override val src: SrcPath,
    override val whenExpr: WhenRef?,
    override val timing: Timing?,
    public val origin: String,
    public val velocity: Value<Vec3>,
    public val speed: SpeedSpec?,
    public val range: Value<Double>?,
    public val radius: Value<Double>?,
    public val hit: ProjectileHit,
    public val motion: List<MotionLaw>?,
    public val block: ProjectileBlock,
    public val gravity: Value<Double>?,
    public val drag: Value<Double>?,
    public val rawOn: Map<ProjectileEvent, List<Any?>>,
    public val handlers: Map<ProjectileEvent, List<OpNode>> = emptyMap(),
    public val lowerHandlers: HandlerLowering? = null,
) : OpNode {
    public fun withHandlers(decoded: Map<ProjectileEvent, List<OpNode>>, lowering: HandlerLowering): ProjectileOp =
        ProjectileOp(id, src, whenExpr, timing, origin, velocity, speed, range, radius, hit, motion, block, gravity, drag, rawOn, decoded, lowering)
}

public object ProjectileOpSpec : OpSpec<ProjectileOp> {
    override val name: String = "projectile"
    override val section: OpSection = OpSection.GAMEPLAY
    override val timing: TimingKind? = TimingKind.EXTENDED

    private val MODES = arrayOf("despawn", "stick", "bounce", "pass")
    private val TARGETS = arrayOf("enemies", "allies", "all", "blocks_only")

    private val rawAny: FieldType<Any> = object : FieldType<Any> {
        override val label = "list of laws"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Any = raw ?: ctx.mismatch(path, label, raw)
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array", "minItems" to 1)
    }

    private val rawHandlers: FieldType<List<Any?>?> = object : FieldType<List<Any?>?> {
        override val label = "non-empty list of ops"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<Any?> {
            val l = raw as? List<*> ?: ctx.mismatch(path, label, raw)
            if (l.isEmpty()) ctx.fail(path, "an empty handler list; leave the event out")
            return l
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array", "minItems" to 1, "items" to linkedMapOf("type" to "object"))
    }

    private val rampFrom = Field.required("from", Types.number)
    private val rampTo = Field.required("to", Types.number)
    private val rampFor = Field.required("for", Types.number, "ramp length in ticks")
    private val rampEase = Field.required("ease", Types.id)
    private val rampFields = Fields.of("speed-ramp", rampFrom, rampTo, rampFor, rampEase)

    private val speedType: FieldType<SpeedSpec> = object : FieldType<SpeedSpec> {
        override val label = "blocks per tick, or {from, to, for, ease}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): SpeedSpec {
            if (raw is Map<*, *>) {
                val v = rampFields.read(raw, ctx, path)
                val ease = v[rampEase]
                if (Easing.fromKey(ease) == null) ctx.fail("$path.ease", "unknown ease '$ease'")
                return SpeedSpec.Ramp(v[rampFrom], v[rampTo], v[rampFor], ease)
            }
            return SpeedSpec.Constant(Types.number.decode(raw, ctx, path))
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("oneOf" to listOf(Types.number.schema(), rampFields.jsonSchema()))
    }

    private val hitTargets = Field.required("targets", Types.enumOf(*TARGETS), "enemies / allies / all / blocks_only")
    private val hitOnce = Field.required("once", Types.bool, "true: the first victim ends the flight; false: pierce, up to `times` victims")
    private val hitTimes = Field.optional("times", Types.nullable(Types.number), null, "pierce count; required when once is false, rejected otherwise")
    private val shapeRadius = Field.required("capsule", Types.number, "capsule radius in blocks")
    private val shapeFrom = Field.required("from", Types.id, "capsule start point of each frame (root / tip / a part point)")
    private val shapeTo = Field.required("to", Types.id, "capsule end point of each frame")
    private val shapeFrames = Field.required("frames", Types.list(Types.id), "rider `frame` ops anchored on this projectile; one swept capsule each")
    private val shapeFields = Fields.of("projectile-hit-shape", shapeRadius, shapeFrom, shapeTo, shapeFrames)
    private val hitShape = Field.optional("shape", Types.nullable(Types.nested(shapeFields)), null, "R43: capsules on rider frames instead of `radius`; exactly one of the two")
    private val hitFields = Fields.of("projectile-hit", hitTargets, hitOnce, hitTimes, hitShape)

    private val blockMode = Field.required("mode", Types.enumOf(*MODES), "despawn / stick / bounce / pass")
    private val blockFor = Field.optional("for", Types.nullable(Types.number), null, "ticks stuck; required for stick, rejected otherwise")
    private val blockRestitution = Field.optional("restitution", Types.nullable(Types.number), null, "speed kept after a bounce; required for bounce, rejected otherwise")
    private val blockTimes = Field.optional("times", Types.nullable(Types.number), null, "maximum bounces; required for bounce, rejected otherwise")
    private val blockFields = Fields.of("projectile-block", blockMode, blockFor, blockRestitution, blockTimes)

    private val onEventFields: Map<ProjectileEvent, Field<List<Any?>?>> =
        ProjectileEvent.entries.associateWith { Field.optional(it.key, rawHandlers, null, "ops run at the ${it.key} event tick (at: 0), event-relative") }

    private val onFields: Fields = Fields.of("projectile-on", *onEventFields.values.toTypedArray())

    private val origin = Field.required("origin", Types.id, "anchor the body is spawned at (muzzle, caster.eye, contact, a frame id)")
    private val velocity = Field.required("velocity", Types.worldVec3, "launch direction (blocks per tick when `speed` is absent)")
    private val speed = Field.optional("speed", Types.nullable(speedType), null, "blocks per tick or a ramp; required with `range` (the end tick must be static); absent = |velocity|")
    private val range = Field.optional("range", Types.nullable(Types.number), null, "blocks of travel after which `end` fires; `range` and/or the timing `for` is required")
    private val radius = Field.optional("radius", Types.nullable(Types.number), null, "hit sphere radius of the body point in blocks; exactly one of `radius` / `hit.shape`")
    private val hit = Field.required("hit", Types.nested(hitFields))
    private val motion = Field.optional("motion", Types.nullable(rawAny), null, "laws on the body frame (spin about forward, ...)")
    private val block = Field.required("block", Types.nested(blockFields))
    private val gravity = Field.optional("gravity", Types.nullable(Types.number), null, "blocks per tick squared, down")
    private val drag = Field.optional("drag", Types.nullable(Types.number), null, "speed fraction lost per tick")
    private val on = Field.optional("on", Types.nullable(Types.nested(onFields)), null, "handlers by event: hit, bounce, stick, unstick, block, expire, end")

    override val fields: Fields = Fields.of("projectile", origin, velocity, speed, range, radius, hit, motion, block, gravity, drag, on)

    override fun decode(id: OpId, src: SrcPath, whenExpr: WhenRef?, timing: Timing?, values: FieldValues, ctx: GrammarContext): Result<ProjectileOp, SkillDecodeError> =
        Grammar.guard {
            val path = "projectile '${id.value}'"
            if (timing?.every != null) ctx.fail("$path.every", "a projectile is not repeated with every; put it in a group")
            if (timing?.until != null) ctx.fail("$path.until", "a projectile ends by itself (range / for / block mode)")
            if (timing?.lasts != null) ctx.fail("$path.lasts", "lasts is for repeated ops; use for")
            if (timing?.span is Span.UntilCastEnds) ctx.fail("$path.for", "for: cast is not a flight length; give ticks")

            val h = values[hit]
            val once = h[hitOnce]
            val times = h[hitTimes]
            if (once is Value.Literal && !once.value && times == null) ctx.fail("$path.hit", "hit.once is false: hit.times (the pierce count) is required")
            if (once is Value.Literal && once.value && times != null) ctx.fail("$path.hit.times", "hit.times only with once: false")
            val shape = h[hitShape]?.let { ProjectileHitShape(it[shapeRadius], it[shapeFrom], it[shapeTo], it[shapeFrames]) }
            if (shape != null && shape.frames.isEmpty()) ctx.fail("$path.hit.shape.frames", "needs at least one frame")
            if (shape != null && values[radius] != null) ctx.fail("$path.radius", "radius and hit.shape are exclusive: the shape replaces the body sphere")
            if (shape == null && values[radius] == null) ctx.fail("$path.radius", "radius (body sphere) or hit.shape (capsules on rider frames) is required")
            val hitSpec = ProjectileHit(h[hitTargets], once, times, shape)

            val b = values[block]
            val mode = b[blockMode]
            val needStick = mode == "stick"
            val needBounce = mode == "bounce"
            if (needStick && b[blockFor] == null) ctx.fail("$path.block", "block.mode stick: block.for (ticks stuck) is required")
            if (!needStick && b[blockFor] != null) ctx.fail("$path.block.for", "block.for only with mode stick")
            if (needBounce && b[blockRestitution] == null) ctx.fail("$path.block", "block.mode bounce: block.restitution is required")
            if (needBounce && b[blockTimes] == null) ctx.fail("$path.block", "block.mode bounce: block.times (maximum bounces) is required")
            if (!needBounce && b[blockRestitution] != null) ctx.fail("$path.block.restitution", "block.restitution only with mode bounce")
            if (!needBounce && b[blockTimes] != null) ctx.fail("$path.block.times", "block.times only with mode bounce")
            val blockSpec = ProjectileBlock(mode, b[blockFor], b[blockRestitution], b[blockTimes])

            if (timing?.span == null && values[range] == null) ctx.fail(path, "either range (blocks) or for (ticks) is required; there is no default flight length")
            if (values[range] != null && values[speed] == null) ctx.fail("$path.range", "range needs speed: the end tick is computed at compile time, not from the launch vector")

            val raw = LinkedHashMap<ProjectileEvent, List<Any?>>()
            val onValues = values[on]
            if (onValues != null) {
                for (e in ProjectileEvent.entries) {
                    val list = onValues[onEventFields.getValue(e)] ?: continue
                    if (!produces(mode, hitSpec.targets, e)) ctx.fail("$path.on.${e.key}", "block.mode $mode (hit.targets ${hitSpec.targets}) never produces the ${e.key} event")
                    raw[e] = list
                }
            }

            val keys = values[motion]?.let { m -> MotionLaws.readLaws(m, ctx.forLaws(com.astarworks.astera.core.application.skillengine.load.grammar.LocalAxes), "$path.motion") }
            ProjectileOp(id, src, whenExpr, timing, values[origin], values[velocity], values[speed], values[range], values[radius], hitSpec, keys, blockSpec, values[gravity], values[drag], raw)
        }

    /** The events a body of this mode can fire (3.6): `pass` has no block events; `blocks_only` has no victim hit. */
    internal fun produces(mode: String, targets: String, event: ProjectileEvent): Boolean = when (event) {
        ProjectileEvent.HIT -> targets != "blocks_only"
        ProjectileEvent.BLOCK -> mode != "pass"
        ProjectileEvent.BOUNCE -> mode == "bounce"
        ProjectileEvent.STICK, ProjectileEvent.UNSTICK -> mode == "stick"
        ProjectileEvent.EXPIRE, ProjectileEvent.END -> true
    }

    override fun lower(op: ProjectileOp, lc: LowerContext): Result<Lowered, SkillDecodeError> {
        val src = lc.src
        val body = lc.self
        fun bad(message: String): Result.Failure<SkillDecodeError> =
            Result.Failure(SkillDecodeError.Invalid(lc.skillId, src.value, "projectile '${op.id.value}': $message"))

        fun ticks(field: String, v: Value<Double>) = staticTicks(lc, field, v)
        fun number(field: String, v: Value<Double>) = staticNumber(lc, field, v)

            val forTicks = (op.timing?.span as? Span.Ticks)?.let { ticks("for", it.ticks).getOr { return Result.Failure(it) } }
            if (forTicks != null && forTicks < 1) return bad("for must be at least 1 tick")
            val range = op.range?.let { number("range", it).getOr { return Result.Failure(it) } }
            if (range != null && range <= 0.0) return bad("range must be positive")

            val speed: ProjectileKinematics.Speed? = when (val s = op.speed) {
                null -> null
                is SpeedSpec.Constant -> ProjectileKinematics.constant(number("speed", s.speed).getOr { return Result.Failure(it) })
                is SpeedSpec.Ramp -> {
                    val r = ticks("speed.for", s.forTicks).getOr { return Result.Failure(it) }
                    if (r < 1) return bad("speed.for must be at least 1 tick")
                    ProjectileKinematics.Speed(number("speed.from", s.from).getOr { return Result.Failure(it) }, number("speed.to", s.to).getOr { return Result.Failure(it) }, r, Easing.fromKey(s.ease) ?: return bad("unknown ease '${s.ease}'"))
                }
            }
            if (speed != null && (speed.from < 0.0 || speed.to < 0.0)) return bad("speed must not be negative")

            val cap = lc.config.maxLifeTicks
            val rangeTicks = if (range != null) {
                val sp = speed ?: return bad("range needs speed")
                ProjectileKinematics.ticksToRange(sp, range, cap) ?: return bad("range $range is not reached within $cap ticks at this speed")
            } else null
            val flight = listOfNotNull(forTicks, rangeTicks).minOrNull() ?: return bad("either range or for is required")
            if (flight > cap) return bad("flight lasts $flight ticks, more than max_life_ticks $cap")

            val stickTicks = op.block.forTicks?.let { ticks("block.for", it).getOr { return Result.Failure(it) } } ?: 0
            if (op.block.mode == "stick" && stickTicks < 1) return bad("block.for must be at least 1 tick")
            val bounces = op.block.times?.let { ticks("block.times", it).getOr { return Result.Failure(it) } } ?: 0
            if (op.block.mode == "bounce" && bounces < 1) return bad("block.times must be at least 1")
            val pierce = op.hit.times?.let { ticks("hit.times", it).getOr { return Result.Failure(it) } } ?: 1
            if (pierce < 1) return bad("hit.times must be at least 1")
            val once = (op.hit.once as? Value.Literal)?.value ?: return bad("hit.once must be a literal true / false")

            val window = ProjectileKinematics.endWindow(flight, stickTicks)
            if (window.max > cap) return bad("flight plus stick lasts ${window.max} ticks, more than max_life_ticks $cap")

            // ---- the spawn row: the body's whole contract with the TablePlayer rides in `look` (key list in the W1-09 report) ----
            val look = LinkedHashMap<String, Val<Any>>()
            look["velocity"] = vec(op.velocity)
            if (speed != null) {
                val ramp = op.speed as? SpeedSpec.Ramp
                if (ramp == null) look["speed"] = Val.Known(speed.to)
                else {
                    look["speed.from"] = Val.Known(speed.from)
                    look["speed.to"] = Val.Known(speed.to)
                    look["speed.for"] = Val.Known(speed.rampTicks.toDouble())
                    look["speed.ease"] = Val.Known(ramp.ease)
                }
            }
            if (range != null) look["range"] = Val.Known(range)
            if (forTicks != null) look["for"] = Val.Known(forTicks.toDouble())
            op.radius?.let { look["radius"] = num(it) }
            op.hit.shape?.let { sh ->
                // frames are named, not resolved: a rider frame may be declared after the projectile; the player finds the sibling instance by op id
                look["hit.shape.radius"] = num(sh.radius)
                sh.frames.forEachIndexed { i, f ->
                    look["hit.shape.frame.$i"] = Val.Known(f)
                    look["hit.shape.frame.$i.from"] = Val.Known(GameplayOps.point(f, sh.from))
                    look["hit.shape.frame.$i.to"] = Val.Known(GameplayOps.point(f, sh.to))
                }
            }
            look["hit.targets"] = Val.Known(op.hit.targets)
            look["hit.once"] = Val.Known(once)
            look["hit.times"] = Val.Known(pierce.toDouble())
            look["block.mode"] = Val.Known(op.block.mode)
            op.block.restitution?.let { look["block.restitution"] = num(it) }
            if (op.block.mode == "bounce") look["block.times"] = Val.Known(bounces.toDouble())
            if (op.block.mode == "stick") look["block.for"] = Val.Known(stickTicks.toDouble())
            op.gravity?.let { look["gravity"] = num(it) }
            op.drag?.let { look["drag"] = num(it) }
            look["end.min"] = Val.Known(window.min.toDouble())
            look["end.max"] = Val.Known(window.max.toDouble())

            val anchor = lc.anchors.resolve(op.origin)?.instance
            val one = Val.Known(Vector3d(1.0, 1.0, 1.0))
            // a document anchor (`anchors:` entry, e.g. `muzzle`) is not an instance: no live cell `anchor:muzzle.*` exists on Paper, so the spawn pose is
            // lowered like a particle's (CEL position from its frame + declared offsets; rotation = that frame's rotation, the shot's aim)
            var spawnPosition: Val<Vector3d> = Val.Live("anchor:${op.origin}.position", null)
            var spawnRotation: Val<Quaterniond> = Val.Live("anchor:${op.origin}.rotation", null)
            if (anchor == null && lc.anchors.document(op.origin) != null) {
                val base = ParticlePlacement.base(lc, op.origin, "projectile").getOr { return Result.Failure(it) }
                    ?: return bad("origin '${op.origin}' is a document anchor whose parent is not declared in this scope")
                spawnPosition = ParticlePlacement.position(lc, op.origin, "projectile").getOr { return Result.Failure(it) }
                val frameName = Regex("^point\\((.+), '(.+)'\\)$").matchEntire(base.frame)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" } ?: base.frame
                spawnRotation = Val.Live("anchor:$frameName", null)
            }
            val rows = ArrayList<Row>()
            rows += Row(
                0, body,
                Action.Spawn(
                    SpawnKind.PROJECTILE_BODY, anchor, op.origin,
                    Pose(spawnPosition, spawnRotation, one),
                    Look(look),
                ),
                src,
            )
            // the body emits no Key / Teleport rows: its position stays live and its closed-form `motion:` is composed into every rider (item 16)
            // the body is an anchor even without `motion:` (its live position), so riders and trails can name it
            run {
                val declared = InstanceId(body.path.dropLast(1) + InstanceId.Segment(op.id, null))
                val family = (op.timing?.times as? Value.Literal)?.value?.toInt()
                (lc.anchors as? AnchorRegistry)?.declare(declared, family, if (op.motion.isNullOrEmpty()) ExprClass.L else ExprClass.T, op.motion ?: emptyList())
            }
            rows += Row(window.max, body, Action.End(body), src)

            // ---- sub-tables: one per event that has handlers, a follow branch, or is `end` (always, so `until: ball.end` closes) ----
            val subTables = ArrayList<SubTable>()
            val instances = arrayListOf(body)
            val events = ArrayList<EventPath>()
            val writes = LinkedHashMap<InstanceId, WriteStats>()

            if (op.rawOn.isNotEmpty() && op.lowerHandlers == null) return bad("on: handlers were never decoded (OpListDecoder attaches them)")
            fun eventPath(e: ProjectileEvent) = EventPath(body.text + "." + e.key)
            val endPath = eventPath(ProjectileEvent.END)
            val now = lc.clockOffset
            val absolute = TickRange(now + window.min, now + window.max)
            val firstStick = TickRange(now + window.min, now + window.max - stickTicks)
            val flightEnd = TickRange(now + flight, now + flight)
            val afterStick = TickRange(now + window.min + stickTicks, now + window.max)

            for (e in ProjectileEvent.entries) {
                if (!produces(op.block.mode, op.hit.targets, e)) continue
                val path = eventPath(e)
                events += path
                val follows: Branch? = when {
                    e == ProjectileEvent.HIT && once -> Branch(endPath, TickRange(0, 0), null, null, endPath)
                    e == ProjectileEvent.BLOCK && op.block.mode == "despawn" -> Branch(endPath, TickRange(0, 0), null, null, endPath)
                    e == ProjectileEvent.UNSTICK -> Branch(endPath, TickRange(0, 0), null, null, endPath)
                    else -> null
                }
                val handlerNodes = op.handlers[e].orEmpty()
                if (handlerNodes.isEmpty() && follows == null && e != ProjectileEvent.END) continue

                var handlerRows: List<Row> = emptyList()
                if (handlerNodes.isNotEmpty()) {
                    val lowering = op.lowerHandlers ?: return bad("on.${e.key} has handlers but no lowering")
                    val prefix = InstanceId(body.path.dropLast(1) + InstanceId.Segment(OpId(op.id.value + "." + e.key), body.path.last().index))
                    val hc = LowerContext(
                        lc.skillId, lc.state, prefix, lc.occurrence, 0, lc.anchors, lc.frames, lc.parts, lc.skills, lc.item, lc.sprites, lc.conditions, lc.exprs,
                        lc.config, lc.seed, com.astarworks.astera.core.domain.skillengine.table.SrcPath(src.value + ".on." + e.key),
                    )
                    val lo = when (val r = lowering.lower(handlerNodes, hc)) {
                        is Result.Success -> r.value
                        is Result.Failure -> return r
                    }
                    handlerRows = lo.rows.sortedBy { it.tick }
                    subTables += lo.subTables
                    instances += lo.instances
                    events += lo.events
                    writes.putAll(lo.writes)
                }
                val (win, firings) = when (e) {
                    ProjectileEvent.HIT -> absolute to (if (once) 1 else pierce)
                    ProjectileEvent.BLOCK -> absolute to (if (op.block.mode == "bounce") bounces + 1 else 1)
                    ProjectileEvent.BOUNCE -> absolute to bounces
                    ProjectileEvent.STICK -> firstStick to 1
                    ProjectileEvent.UNSTICK -> afterStick to 1
                    ProjectileEvent.EXPIRE -> flightEnd to 1
                    ProjectileEvent.END -> absolute to 1
                }
                subTables += SubTable(path, lc.state, win, firings, handlerRows, listOfNotNull(follows))
            }
            return Result.Success(Lowered(rows, subTables, instances, events.distinct(), writes))
    }

    private inline fun <T> Result<T, SkillDecodeError>.getOr(fail: (SkillDecodeError) -> Nothing): T = when (this) {
        is Result.Success -> value
        is Result.Failure -> fail(error)
    }

    private fun staticTicks(lc: LowerContext, field: String, v: Value<Double>): Result<Int, SkillDecodeError> =
        lc.requireStaticTiming(lc.src.value + "." + field, field, v)

    private fun staticNumber(lc: LowerContext, field: String, v: Value<Double>): Result<Double, SkillDecodeError> = when (v) {
        is Value.Literal -> Result.Success(v.value)
        else -> when (val r = staticTicks(lc, field, v)) {
            is Result.Success -> Result.Success(r.value.toDouble())
            is Result.Failure -> r
        }
    }

    private fun num(v: Value<Double>): Val<Double> = when (v) {
        is Value.Literal -> Val.Known(v.value)
        is Value.ParamRef -> Val.Live("param:${v.name}", null)
        is Value.Expr -> Val.Live("expr:${v.source}", null)
    }

    private fun vec(v: Value<Vec3>): Val<Vector3d> = when (v) {
        is Value.Literal -> Val.Known(Vector3d(v.value.x, v.value.y, v.value.z))
        is Value.ParamRef -> Val.Live("param:${v.name}", null)
        is Value.Expr -> Val.Live("expr:${v.source}", null)
    }
}

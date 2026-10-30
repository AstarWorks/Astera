package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.CastTrace
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.LineGeometry
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.skillengine.FakeSkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
import com.astarworks.astera.core.application.skillengine.runtime.player.RowScope
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.application.skillengine.runtime.player.CellExprs
import com.astarworks.astera.core.application.skillengine.runtime.player.EntityQueryExprHost
import com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings
import com.astarworks.astera.core.domain.skillengine.expr.ExprHost
import com.astarworks.astera.core.domain.skillengine.expr.ExprValue
import com.astarworks.astera.core.domain.skillengine.expr.FrameLookup
import com.astarworks.astera.core.domain.geometry.math.Frame as SimFrame
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import com.astarworks.astera.core.domain.skillengine.table.EntityRef
import com.astarworks.astera.core.domain.skillengine.table.TargetRule
import com.astarworks.astera.core.domain.skillengine.table.TargetSet
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import java.util.UUID

/**
 * In-memory gateways for the table player: every call becomes a [RecordedCall] stamped with [tick]. The harness sets [tick]
 * before each `player.tick(now)`. Handles are numbered in spawn order; the call's `instance` is `#<ordinal>`.
 *
 * No world exists: [victims] is what `nearby` returns (default none), so target-dependent rows (`damage`, `status`, `effect`)
 * produce no calls unless a case scripts victims. Live values resolve to fixed stand-ins ([casterPosition], identity
 * rotation); table rows never depend on their value (positions are never compared).
 */
class GatewayRecorder(val caster: UUID = UUID(0L, 1L), val castWorld: WorldId = WorldId("world")) : CastTrace {
    var tick: Int = 0
        set(value) {
            field = value
            victimMotion?.let { victims = it(value) }
        }
    var victims: List<HitCandidate> = emptyList()

    /** When set, [victims] is re-read from it at the start of every tick: the scripted motion of a victim that walks. */
    var victimMotion: ((Int) -> List<HitCandidate>)? = null
    var casterPosition: Vector3d = Vector3d(0.0, 1.0, 0.0)
    var casterYawDeg: Double = 0.0
    var casterPitchDeg: Double = 0.0

    /**
     * Off (default): live caster cells answer the old stand-ins (position [casterPosition], identity rotation, yaw only). On (the parity
     * oracle): they answer what `PaperLiveValueSource` answers for a caster standing with feet at `casterPosition - (0,1,0)` looking
     * along ([casterYawDeg], [casterPitchDeg]) - feet / eye / body / forward / hands and the yaw- or pitch-carrying rotations.
     */
    var faithfulPose: Boolean = false

    /** The item id of the skill being played (`play` sets it), as the player puts it in every [CastRef]. */
    var castItem: String? = null

    /** Off: the caster is not an online player, so a main-hand mesh or a skin bone is refused exactly as Paper refuses it (no owner to draw). */
    var casterOnline: Boolean = true

    /** The item each main-hand mesh was drawn with (CastRef.heldItem, fixed at cast start); a rig never reads the caster's live hand. */
    val heldMeshItems: MutableList<String> = ArrayList()

    /** Values the cast reads through `Val.Live` numbers, by live name. A name not set here reads 0.0; every read is in [liveReads]. */
    val liveNums: MutableMap<String, Double> = HashMap()
    private val reads = ArrayList<String>()
    val liveReads: List<String> get() = reads

    /** Every port call that carries a [CastRef], as `port.method` to the ref it got (R40: each must be this cast's own). */
    private val refs = ArrayList<Pair<String, CastRef>>()
    val castRefs: List<Pair<String, CastRef>> get() = refs

    /** The trace of the player (state enter / exit, events, calls) as `verb:what@tick`; kept out of [calls]. */
    private val traceLog = ArrayList<String>()
    val trace: List<String> get() = traceLog

    override fun enter(cast: CastHandle, state: StatePath, tick: Int) { traceLog += "enter:${state.value}@$tick" }
    override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) { traceLog += "exit:${state.value}:${reason.name}@$tick" }
    override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) { traceLog += "event:${path.value}@$tick" }
    override fun call(cast: CastHandle, callId: InstanceId, tick: Int) { traceLog += "call:${callId.text}@$tick" }
    override fun drain(cast: CastHandle, tick: Int, rows: Int, until: Int) { traceLog += "drain:$rows:$until@$tick" }
    override fun cut(cast: CastHandle, row: String, tick: Int, state: StatePath, reason: ExitReason) { traceLog += "cut:$row:${state.value}:${reason.name}@$tick" }
    override fun frame(cast: CastHandle, op: InstanceId, tick: Int, position: Vec3, rotation: com.astarworks.astera.core.domain.geometry.math.Quaternion) =
        oracleOnly("frame", op.text, "pos=${OracleFmt.v(position)} rot=${OracleFmt.q(rotation)}", op.text)

    /** The op instance whose row is playing; particle, sound and area-query rows are attributed to it. */
    private var rowOp: String? = null

    /** The volume + op the player announced just before its next `nearby` broad phase (consumed by that call). */
    private var pendingVolume: Pair<String, ResolvedVolume>? = null

    override fun row(cast: CastHandle, op: InstanceId, tick: Int) { rowOp = op.text }

    /** Entities that are mobs (everything else is a player): `react player:` clips are recorded as played for a player and as `react_skip` for these. */
    val mobs: MutableSet<UUID> = HashSet()

    override fun reactClipSkipped(cast: CastHandle, op: InstanceId, tick: Int, victim: UUID?) {
        add("react_skip", victim.toString(), "only=player target=mob")
    }
    override fun statusSkippedDead(cast: CastHandle, op: InstanceId, tick: Int, victim: UUID?) {
        add("status_skip", victim.toString(), "dead_victim")
    }
    override fun hitVolume(cast: CastHandle, op: InstanceId, volume: ResolvedVolume, tick: Int) { pendingVolume = op.text to volume }

    private fun seen(port: String, cast: CastRef) {
        refs += port to cast
    }

    private val log = ArrayList<RecordedCall>()
    private val ordinals = HashMap<Long, Int>()
    private var nextHandle = 0L

    val calls: List<RecordedCall> get() = log

    /** Everything the oracle golden records: [calls] plus rows that exist only for the oracle (hit volumes, trail shape), each with its [RecordedCall.detail]. */
    private val oracleLog = ArrayList<RecordedCall>()
    val oracleRows: List<RecordedCall> get() = oracleLog

    private fun add(verb: String, instance: String, payload: String, detail: String = "") {
        val c = RecordedCall(tick, verb, instance, payload)
        c.detail = detail
        if (verb == "particle" || verb == "sound") c.op = if (faithfulPose) rowOp ?: error("$verb row issued outside any row: no op to attribute it to") else rowOp
        log += c
        oracleLog += c
    }

    /** A row only the oracle golden sees; [calls] (and so every other scenario) is untouched. */
    private fun oracleOnly(verb: String, instance: String, detail: String, op: String? = null) {
        val c = RecordedCall(tick, verb, instance, "")
        c.detail = detail
        c.op = op
        oracleLog += c
    }

    private val cells = CellExprs()

    private val PACK_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

    private fun spawned(cast: CastRef, kind: String, detail: String = ""): VisualHandle {
        seen("visual.spawn.$kind", cast)
        val h = VisualHandle(nextHandle++)
        ordinals[h.id] = ordinals.size
        add("spawn", name(h), "kind=$kind", detail)
        return h
    }

    private fun name(h: VisualHandle): String = "#" + (ordinals[h.id] ?: -1)

    /** The lock sink the platform's `PaperPlayerHooks` is: pitch / yaw go through `lockLook`, so a played lock shows as a `lock` call. */
    /** (display, field, value) of every `set` the player handed to the display sink. */
    val displaySets = ArrayList<Triple<String, String, String>>()

    /** Every display `write` as (tick, handle name, pose): kept out of [calls] so the oracle diff still compares verbs and payloads only. */
    val writtenPoses = ArrayList<Triple<Int, String, VisualPose>>()
    /** [casterPosition] at the moment of each entry of [writtenPoses] (same index): lets a scenario compare a mesh with the caster's pose of that tick. */
    var lastBodyHolds: List<com.astarworks.astera.core.domain.animation.skinrig.SkinRigHold> = emptyList()
    val writtenCasterAt = ArrayList<Vector3d>()

    /** Where a guard sees the guarded caster (feet, yaw): null = the origin facing +z. A duel sets it to the victim's pose. */
    var guardCaster: com.astarworks.astera.core.domain.geometry.math.Frame? = null

    /** Where a guard sees `event.attacker` / `target`: null = 2 blocks ahead of the origin. */
    var guardOther: com.astarworks.astera.core.domain.geometry.Vec3? = null

    val hooks: com.astarworks.astera.core.application.skillengine.runtime.player.PlayerHooks =
        object : com.astarworks.astera.core.application.skillengine.runtime.player.PlayerHooks {
            private val compiler = com.astarworks.astera.core.application.skillengine.expr.cel.CelExprCompiler()

            /**
             * PaperPlayerHooks.guard without Paper: expands `cond.<name>`, compiles the CEL against the v3 variable spec and evaluates it.
             * The sim has no entities, so the caster stands at the origin and the `event.attacker` / `target` of every event stands 2 blocks ahead.
             */
            override fun guard(cast: CastHandle, guard: com.astarworks.astera.core.domain.skillengine.table.Guard, conditions: Map<String, String>, binding: EventBinding?, localTick: Int, seed: Long): Boolean? {
                val source = when (guard) {
                    is com.astarworks.astera.core.domain.skillengine.table.Guard.Expr -> guard.source
                    is com.astarworks.astera.core.domain.skillengine.table.Guard.Named -> "cond.${guard.cond}"
                }
                val expanded = (com.astarworks.astera.core.application.skillengine.expr.cel.CondBindings.expand("sim", "guard", source, conditions)
                    as? com.astarworks.astera.core.domain.Result.Success)?.value ?: return null
                val expr = (compiler.compile(expanded, com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings.spec(null), com.astarworks.astera.core.domain.skillengine.expr.ExprType.BOOL)
                    as? com.astarworks.astera.core.domain.Result.Success)?.value ?: return null
                val ahead = guardOther ?: com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 2.0)
                val vars = com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings.values(
                    null, stateTick = localTick, eventI = binding?.index ?: 0,
                    target = com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings.Binding(pos = ahead),
                )
                val world = com.astarworks.astera.core.application.skillengine.runtime.player.EntityQueryExprHost(query, CastRef(cast.value, this@GatewayRecorder.caster, castWorld, castItem))
                // world functions read the recorder's entity query (victims / allies); the frame functions are not served by this host
                val host = object : com.astarworks.astera.core.domain.skillengine.expr.ExprHost {
                    override fun aimPoint(caster: com.astarworks.astera.core.domain.skillengine.expr.ExprValue.Ref, range: Double): Vec3 = error("aim_point is not served by the sim guard host")
                    override fun lookDirection(caster: com.astarworks.astera.core.domain.skillengine.expr.ExprValue.Ref): Vec3 = error("look_dir is not served by the sim guard host")
                    override fun hasItem(entity: com.astarworks.astera.core.domain.skillengine.expr.ExprValue.Ref, item: String): Boolean =
                        items.hasItem(CastRef(cast.value, this@GatewayRecorder.caster, castWorld, castItem), this@GatewayRecorder.caster, item)
                    override fun nearestEnemyDist(from: Vec3): Double = world.nearestEnemyDist(from)
                    override fun enemyInCone(from: Vec3, dir: Vec3, range: Double, halfAngleDeg: Double): Boolean = world.enemyInCone(from, dir, range, halfAngleDeg)
                }
                val frames = object : com.astarworks.astera.core.domain.skillengine.expr.FrameLookup {
                    override fun worldPose(frame: String, point: String?) =
                        if (frame == "caster") guardCaster ?: com.astarworks.astera.core.domain.geometry.math.Frame(com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0))
                        else com.astarworks.astera.core.domain.geometry.math.Frame(ahead)
                    override fun velocity(frame: String, point: String?) = com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0)
                }
                // like PaperPlayerHooks: a guard that cannot evaluate here (it needs a cast host, e.g. nearest_enemy_dist) answers null = a fault, false
                return try { (expr.eval(com.astarworks.astera.core.domain.skillengine.expr.ExprEnv(vars, frames, seed = seed, host = host)) as? com.astarworks.astera.core.domain.skillengine.expr.ExprValue.Bool)?.value } catch (e: RuntimeException) { null }
            }

            /** The display sink `PaperPlayerHooks` is: a `set` of text_opacity / color / text lands on the display, kept here instead of becoming a call. */
            override fun setDisplayField(handle: VisualHandle, field: String, value: Double): Boolean {
                displaySets += Triple(name(handle), field, RecordedCall.num(value))
                return true
            }

            override fun setDisplayText(handle: VisualHandle, field: String, value: String): Boolean {
                displaySets += Triple(name(handle), field, value)
                return true
            }

            override fun lock(caster: UUID, move: com.astarworks.astera.core.domain.skillengine.table.LockMove, pitch: Boolean, yaw: Boolean, untilTick: Int): Boolean {
                if (pitch || yaw) gameplay.lockLook(caster, pitch, yaw, untilTick)
                return true
            }

            override fun unlock(caster: UUID, move: com.astarworks.astera.core.domain.skillengine.table.LockMove, pitch: Boolean, yaw: Boolean) {
                if (pitch || yaw) gameplay.unlockLook(caster)
            }
        }

    val visual: ISkillVisualGateway = object : ISkillVisualGateway {
        override fun spawnMesh(cast: CastRef, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook, view: DisplayView, glow: String?): VisualHandle {
            // Paper: the main-hand kind draws the model of the cast's item (CastRef.heldItem), so an offline caster or a cast without an item refuses the spawn (PaperSkillVisualGateway.dead); the live hand is never consulted
            if (com.astarworks.astera.core.application.skillengine.load.MeshModels.resolve(item, part, emptyMap()) is com.astarworks.astera.core.application.skillengine.load.MeshModel.HeldItem) {
                if (!casterOnline || cast.heldItem == null) error("skill visual 'mesh $item${if (casterOnline) " (the cast carries no item)" else ""}' could not be spawned: owner offline or display refused")
                heldMeshItems += cast.heldItem!!
            }
            // the Paper gateway refuses a model id NamespacedKey.fromString rejects (e.g. a doubled `astera:astera:`)
            (com.astarworks.astera.core.application.skillengine.load.MeshModels.resolve(item, part, emptyMap()) as? com.astarworks.astera.core.application.skillengine.load.MeshModel.Pack)?.let {
                check(PACK_KEY.matches(it.key)) { "invalid item model '${it.key}' (mesh item '$item' part '$part')" }
            }
            return spawned(cast, "item", "item=${OracleFmt.s(item)} part=${OracleFmt.s(part)} ${OracleFmt.pose(pose)} bright=${brightness ?: "-"} model=${OracleFmt.s(look.itemModel)} mode=${look.mode} rigshade=${look.rigShading} shader=${OracleFmt.s(look.shaderEffect)} glow=${OracleFmt.s(glow)} ${OracleFmt.view(view)}")
        }

        override fun spawnSkinBone(cast: CastRef, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose): VisualHandle {
            if (!casterOnline) error("skill visual 'skin bone ${bone.yamlName}' could not be spawned: owner offline or display refused")
            return spawned(cast, "skin_bone", "bone=${bone.yamlName} slim=$slim ghost=$ghost ${OracleFmt.pose(pose)}")
        }

        override fun spawnPlate(cast: CastRef, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) =
            spawned(cast, "sprite", "sprite=${OracleFmt.s(sprite)} alpha=${OracleFmt.f(alpha)} tint=${OracleFmt.s(tintHex)} seethrough=$seeThrough ${OracleFmt.pose(pose)} bright=${brightness ?: "-"} glow=${OracleFmt.s(glow)} ${OracleFmt.view(view)}")

        override fun spawnBlock(cast: CastRef, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?) =
            spawned(cast, "block", "block=${OracleFmt.s(block)} ${OracleFmt.pose(pose)} bright=${brightness ?: "-"} rigshade=$rigShading glow=${OracleFmt.s(glow)} ${OracleFmt.view(view)}")

        override fun spawnText(cast: CastRef, text: String, backgroundArgbHex: String, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) =
            spawned(cast, "text", "text=${OracleFmt.s(text)} bg=${OracleFmt.s(backgroundArgbHex)} shadow=$shadow seethrough=$seeThrough ${OracleFmt.pose(pose)} bright=${brightness ?: "-"} ${OracleFmt.view(view)}")

        override fun spawnTrail(cast: CastRef, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) =
            spawned(cast, "ribbon", "ticks=$ticks width=${OracleFmt.f(widthFrac)} color=${OracleFmt.s(colorHex)} alpha=${OracleFmt.f(alpha)}")

        override fun spawnLine(cast: CastRef, vertices: List<Vec3>, thickness: Double, colorHex: String, alpha: Double): VisualHandle {
            val from = vertices.first()
            val to = vertices.last()
            // a straight thread prints exactly the two-end form (goldens unchanged); a polyline adds its vertices and one crossed pair per segment
            val quads = (0 until vertices.size - 1).joinToString(",") { s ->
                val a = vertices[s]
                val b = vertices[s + 1]
                LineGeometry.planes(a, b, thickness, Vec3(0.0, 1.0, 0.0) + (a + b) * 0.5).joinToString(",") { "[${OracleFmt.v(it.along)}|${OracleFmt.v(it.across)}]" }
            }
            val verts = if (vertices.size > 2) " verts=${vertices.joinToString(";") { OracleFmt.v(it) }}" else ""
            return spawned(cast, "ribbon", "from=${OracleFmt.v(from)} to=${OracleFmt.v(to)}$verts thick=${OracleFmt.f(thickness)} color=${OracleFmt.s(colorHex)} alpha=${OracleFmt.f(alpha)} quads=$quads")
        }

        override fun updateLine(handle: VisualHandle, vertices: List<Vec3>) =
            add("line", name(handle), "", "from=${OracleFmt.v(vertices.first())} to=${OracleFmt.v(vertices.last())}" + if (vertices.size > 2) " verts=${vertices.joinToString(";") { OracleFmt.v(it) }}" else "")

        override fun spawnScreenVeil(cast: CastRef, viewer: UUID, sprite: String, distance: Double, scale: Double, colorHex: String?, maxScreenFraction: Double, ticks: Int) =
            spawned(cast, "veil", "viewer=$viewer sprite=${OracleFmt.s(sprite)} dist=${OracleFmt.f(distance)} scale=${OracleFmt.f(scale)} color=${OracleFmt.s(colorHex)} maxfrac=${OracleFmt.f(maxScreenFraction)} ticks=$ticks")

        override fun updateScreenVeil(handle: VisualHandle, age: Int) = add("veil", name(handle), "age=$age")

        override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) {
            writtenPoses += Triple(tick, name(handle), pose)
            writtenCasterAt += Vector3d(casterPosition.x, casterPosition.y, casterPosition.z)
            add("write", name(handle), "dur=$interpolationTicks", OracleFmt.pose(pose))
        }

        override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) = add("trail", name(handle), "", "root=${OracleFmt.v(root)} tip=${OracleFmt.v(tip)}")

        override fun remove(handle: VisualHandle) = add("remove", name(handle), "")

        override fun sprite(sprite: String): SpriteInfo = SpriteInfo(1, 32)

        override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) =
            oracleOnly("trail_look", name(handle), "fade=${OracleFmt.f(fadePower)} style=${OracleFmt.s(style)}")

        override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) =
            oracleOnly(
                "trail_shape", name(handle),
                "bands=" + bands.joinToString(";") { "${OracleFmt.f(it.from)}~${OracleFmt.f(it.to)}~${OracleFmt.s(it.color)}~${OracleFmt.f(it.alpha)}~${OracleFmt.s(it.colorTo)}~${OracleFmt.s(it.block)}" }.ifEmpty { "-" } +
                    " edge=${edge?.let { "${OracleFmt.s(it.block)}~${OracleFmt.f(it.width)}" } ?: "-"} rim=${rim?.let { "${OracleFmt.s(it.color)}~${OracleFmt.f(it.alpha)}~${OracleFmt.f(it.width)}" } ?: "-"} step=${OracleFmt.f(stepDeg)} double=$doubleSided",
            )

        override fun setTrailWidth(handle: VisualHandle, widthFrac: Double) = add("trail_width", name(handle), "w=%.3f".format(widthFrac), "width=${OracleFmt.f(widthFrac)}")
    }

    /** Every particle burst as (tick, particle, world position). */
    val emittedAt = ArrayList<Triple<Int, String, Vec3>>()

    val particles: ISkillParticleGateway = object : ISkillParticleGateway {
        override fun emit(cast: CastRef, burst: ParticleBurst) {
            seen("particles.emit", cast)
            emittedAt += Triple(tick, burst.particle, burst.at)
            add(
                "particle", "-", "type=${burst.particle}" + (burst.count?.let { " count=$it" } ?: ""),
                "at=${OracleFmt.v(burst.at)} off=${OracleFmt.v(burst.offset)} speed=${OracleFmt.f(burst.speed)} color=${OracleFmt.s(burst.colorHex)} size=${burst.dataSize?.let { OracleFmt.f(it) } ?: "-"} " +
                    "dir=${burst.dir?.let { OracleFmt.v(it) } ?: "-"} spread=${OracleFmt.f(burst.spreadDeg)} vel=${burst.inheritVelocity?.let { OracleFmt.v(it) } ?: "-"} look=${OracleFmt.v(burst.look)} seed=${burst.seed}",
            )
        }
    }

    /** Where each sound played, in call order (kept out of [calls]: the oracle diff compares verbs and payloads only). */
    val soundPositions = ArrayList<Pair<Int, Vec3>>()

    val sounds: ISkillSoundGateway = object : ISkillSoundGateway {
        override fun play(cast: CastRef, sound: String, at: Vec3, category: String, volume: Double, pitch: Double) {
            seen("sounds.play", cast)
            soundPositions += tick to at
            add("sound", "-", "sound=$sound cat=$category vol=${RecordedCall.num(volume)} pitch=${RecordedCall.num(pitch)}", "at=${OracleFmt.v(at)}")
        }
    }

    /** Victims whose death the next [ISkillGameplayGateway.damage] reports (it returns true for them): the sim stand-in for a hit that kills. */
    val killOnDamage: MutableSet<UUID> = HashSet()

    val gameplay: ISkillGameplayGateway = object : ISkillGameplayGateway {
        override fun damage(skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean, ignoreArmor: Boolean): Boolean {
            add(
                "damage", victim.toString(), components.joinToString(" ") { "${it.kind}=${RecordedCall.num(it.amount)}" },
                "skill=${OracleFmt.s(skillId)} at=${OracleFmt.v(hitPoint)} ignoreinv=$ignoreInvulnerability ignorearmor=$ignoreArmor",
            )
            return victim in killOnDamage
        }

        override fun cancelIncomingHit(cast: CastRef, attacker: UUID?) = add("cancelIncomingHit", "-", "attacker=${attacker ?: "-"}")

        override fun statusStacks(entity: UUID, statusId: String): Int = 0

        override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) =
            add("status", victim.toString(), "status=$statusId stacks=$stacks for=$durationTicks")

        override fun rollChance(chance: Double): Boolean = true

        override fun removeStatus(victim: UUID, statusId: String) = add("effect", victim.toString(), "remove_status=$statusId")

        override fun cleanse(victim: UUID, negativeOnly: Boolean) = add("effect", victim.toString(), "cleanse")

        override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) = add("effect", victim.toString(), "ignite=$durationTicks")

        override fun silence(caster: UUID, victim: UUID, durationTicks: Int) = add("effect", victim.toString(), "silence=$durationTicks")

        override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) =
            add("effect", victim.toString(), "attribute=$attribute op=$operation amount=${RecordedCall.num(amount)} for=${durationTicks ?: "-"}")

        override fun restoreResource(victim: UUID, resource: String, amount: Double) = add("effect", victim.toString(), "resource=$resource amount=${RecordedCall.num(amount)}")

        override fun impulse(victim: UUID, vector: Vec3) = add("effect", victim.toString(), "impulse x=${RecordedCall.num(vector.x)} y=${RecordedCall.num(vector.y)} z=${RecordedCall.num(vector.z)}")

        override fun freezeEntities(entities: List<UUID>, ticks: Int) = entities.forEach { add("effect", it.toString(), "freeze=$ticks") }

        override fun lockLook(caster: UUID, pitch: Boolean, yaw: Boolean, untilTick: Int) = add("lock", "-", "pitch=$pitch yaw=$yaw until=$untilTick")

        override fun unlockLook(caster: UUID) = add("unlock", "-", "")

        override fun consumeStatus(cast: CastRef, entity: UUID, status: String) {
            seen("gameplay.consumeStatus", cast)
            add("effect", entity.toString(), "consume_status=$status")
        }

        override fun cameraShake(cast: CastRef, viewer: UUID, intensity: Double, ticks: Int, from: Vec3?) {
            seen("gameplay.cameraShake", cast)
            add("effect", viewer.toString(), "camera_shake=${RecordedCall.num(intensity)} ticks=$ticks from=${if (from == null) "none" else "hit"}")
        }

        override fun screenFlash(cast: CastRef, viewer: UUID, overlay: String, colorRgb: Int?, fadeInTicks: Int, stayTicks: Int, fadeOutTicks: Int) {
            seen("gameplay.screenFlash", cast)
            add("effect", viewer.toString(), "screen_flash=$overlay color=${colorRgb?.let { "#%06x".format(it) } ?: "default"} in=$fadeInTicks stay=$stayTicks out=$fadeOutTicks")
        }

        override fun stopSound(cast: CastRef, viewer: UUID, sound: String) {
            seen("gameplay.stopSound", cast)
            add("effect", viewer.toString(), "stop_sound=$sound")
        }

        /** The shipped `content/animations` clips, like the Paper registry (a `rig` op needs the clip's timeline). */
        override fun bodyClip(anim: String) = com.astarworks.astera.gamesim.SimClips.clip(anim)

        override fun bodySkeleton() = com.astarworks.astera.gamesim.SimClips.bodySkeleton()

        override fun isPlayer(entity: UUID): Boolean = entity !in mobs

        override fun playBodyClip(cast: CastRef, target: UUID, anim: String, speed: Double, holds: List<com.astarworks.astera.core.domain.animation.skinrig.SkinRigHold>) {
            lastBodyHolds = holds
            seen("gameplay.playBodyClip", cast)
            add("effect", target.toString(), "body=$anim speed=${RecordedCall.num(speed)}")
        }

        override fun stopBodyClip(cast: CastRef, target: UUID, anim: String) {
            seen("gameplay.stopBodyClip", cast)
            add("effect", target.toString(), "body_stop=$anim")
        }

        override fun setHeldModel(cast: CastRef, caster: UUID, model: String) {
            seen("gameplay.setHeldModel", cast)
            add("effect", caster.toString(), "held_model=$model")
        }

        override fun clearHeldModel(cast: CastRef, caster: UUID) {
            seen("gameplay.clearHeldModel", cast)
            add("effect", caster.toString(), "held_model_clear")
        }

        override fun hurt(cast: CastRef, target: UUID, from: Vec3) {
            seen("gameplay.hurt", cast)
            add("effect", target.toString(), "hurt")
        }
    }

    /** Entities on the caster's team: `isAlly(caster, x)` is true for these (and for the caster itself). */
    val allies: MutableSet<UUID> = HashSet()

    val query: ISkillEntityQuery = object : ISkillEntityQuery {
        override fun isAlly(cast: CastRef, a: UUID, b: UUID): Boolean {
            seen("query.isAlly", cast)
            val me = this@GatewayRecorder.caster
            return (a == me && (b == me || b in allies)) || (b == me && a in allies)
        }

        override fun nearby(cast: CastRef, point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> {
            seen("query.nearby", cast)
            val announced = pendingVolume
            pendingVolume = null
            val shape = announced?.second?.let { OracleFmt.volume(it) } ?: "shape=area"
            oracleOnly("hit", "-", "at=${OracleFmt.v(point)} r=${OracleFmt.f(radius)} $shape", announced?.first ?: if (faithfulPose) rowOp ?: error("hit query outside any row: no op to attribute it to") else rowOp)
            return victims.filter { it.entityId != excluding }
        }

        override fun rootOf(cast: CastRef, entity: UUID): EntityRoot? {
            seen("query.rootOf", cast)
            victims.firstOrNull { it.entityId == entity }?.let { return EntityRoot(it.feet, it.yawDeg, 0.0, it.eyeHeight ?: 0.0, 1.8) }
            return if (entity == caster) EntityRoot(Vec3(casterPosition.x, casterPosition.y - 1.0, casterPosition.z), casterYawDeg, casterPitchDeg, 1.62, 1.8) else null
        }

        override fun raycastBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit? {
            seen("query.raycastBlock", cast)
            return blockRaycast(cast, from, to)
        }
    }

    /** The world a projectile's swept segment is cast against; the oracle recorder has none (no block ever hit), the sim engine plugs in its block world. */
    var blockRaycast: (CastRef, Vec3, Vec3) -> BlockHit? = { _, _, _ -> null }

    /** Caster motion (R40): `teleport` moves [casterPosition] (feet + 1) so the next `rootOf` sees it; [wall] answers `probeBlock`. */
    var wall: (Vec3, Vec3) -> BlockHit? = { _, _ -> null }
    var velocity: Vec3 = Vec3.ZERO

    val movement: ISkillMovementGateway = object : ISkillMovementGateway {
        override fun teleport(cast: CastRef, entity: UUID, pos: Vec3, yaw: Double?, pitch: Double?) {
            seen("movement.teleport", cast)
            if (entity == this@GatewayRecorder.caster) casterPosition = Vector3d(pos.x, pos.y + 1.0, pos.z)
            add("move", "-", "to=${RecordedCall.num(pos.x)},${RecordedCall.num(pos.y)},${RecordedCall.num(pos.z)}", "yaw=${yaw?.let { OracleFmt.f(it) } ?: "-"} pitch=${pitch?.let { OracleFmt.f(it) } ?: "-"}")
        }

        override fun velocity(cast: CastRef, entity: UUID): Vec3 {
            seen("movement.velocity", cast)
            return velocity
        }

        override fun addVelocity(cast: CastRef, entity: UUID, v: Vec3) {
            seen("movement.addVelocity", cast)
            velocity = velocity + v
            add("move", "-", "add_velocity", "v=${OracleFmt.v(v)}")
        }

        override fun probeBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit? {
            seen("movement.probeBlock", cast)
            return wall(from, to)
        }

        override fun invulnerable(cast: CastRef, entity: UUID, ticks: Int) {
            seen("movement.invulnerable", cast)
            add("move", "-", "invulnerable=$ticks")
        }

        override fun faceMovement(cast: CastRef, entity: UUID, dir: Vec3) {
            seen("movement.faceMovement", cast)
            add("move", "-", "face", "dir=${OracleFmt.v(dir)}")
        }
    }

    /** Items the caster holds, by id; `consume` takes what is there and says whether the whole count was found. */
    val stock: MutableMap<String, Int> = HashMap()

    val items: ISkillItemGateway = object : ISkillItemGateway {
        override fun consume(cast: CastRef, holder: UUID, item: String, count: Int): Boolean {
            seen("items.consume", cast)
            val have = stock[item] ?: 0
            stock[item] = (have - count).coerceAtLeast(0)
            add("item", item, "consume=$count")
            return have >= count
        }

        override fun cooldown(cast: CastRef, skill: String, ticks: Int) {
            seen("items.cooldown", cast)
            add("item", skill, "cooldown=$ticks")
        }

        override fun hasItem(cast: CastRef, holder: UUID, item: String): Boolean {
            seen("items.hasItem", cast)
            return (stock[item] ?: 0) > 0
        }

        override fun applyGate(cast: CastRef, skill: String, cooldown: Boolean, cost: Boolean) {
            seen("items.applyGate", cast)
            add("item", skill, "gate cooldown=$cooldown cost=$cost")
        }
    }

    /** Block world under the real ledger (W2-01): the player calls its `tick` and `revertAll`. */
    val world = FakeSkillWorldGateway()

    val live: LiveValueSource = object : LiveValueSource {
        /**
         * What the Paper host resolves (`PaperLiveValueSource`): the caster / contact / target fields and the CEL forms `= ...` / `expr:...`.
         * Anything else (an instance anchor, a state-qualified id, a param cell) is the table player's own state; reaching the host with
         * it is the fault Paper raises, so the sim raises it too instead of answering a stand-in.
         */
        private fun paperResolvable(name: String): Boolean {
            if (name.startsWith("= ") || name.startsWith("expr:")) return true
            val bare = name.removePrefix("anchor:")
            val root = bare.substringBefore('.')
            if (root == "caster") return bare == "caster" || bare.removePrefix("caster.") in PAPER_CASTER_FIELDS
            if (bare == "event.victim" || bare == "event.attacker") return true
            if (bare == "victim" || bare == "target" || bare.startsWith("victim.")) return true
            return bare == "contact" || (bare.contains('.') && (root == "contact" || root == "target"))
        }

        /** The `caster.<field>` names `PaperLiveValueSource` answers (feet, pos, position, look, eye, body, forward). */
        private val PAPER_CASTER_FIELDS = setOf("feet", "pos", "position", "look", "eye", "body", "forward")

        /** `PaperLiveValueSource.rot`: any `caster` / `caster.*` basis (yaw, plus pitch for look / eye), or a CEL cell; the bare contact basis; no target rotation. */
        private fun paperRotatable(name: String): Boolean {
            if (name.startsWith("= ") || name.startsWith("expr:")) return true
            val bare = name.removePrefix("anchor:")
            return bare == "caster" || bare.startsWith("caster.") || bare == "contact" || bare == "victim" || bare == "target" || bare.startsWith("victim.")
        }

        private fun requireResolvable(name: String, wanted: String) {
            if (if (wanted == "a rotation") !paperRotatable(name) else !paperResolvable(name)) {
                throw IllegalArgumentException("live cell '$name' is not resolvable as $wanted on Paper (instance anchors, param cells need the table player's own state)")
            }
        }

        /** The Paper CEL environment with the sim's caster standing at [casterPosition]: the same [CellExprs] (compile env + served host functions) Paper uses. */
        /** As `PaperLiveValueSource.victimFrame`: the bound victim's body centre (feet / root / eye / body points), yaw only; null without a bound victim. */
        private fun victimFrame(binding: EventBinding?, point: String?): SimFrame? {
            val id = binding?.victim?.id ?: return null
            val c = victims.firstOrNull { it.entityId == id } ?: return null
            val o = when (point) {
                null, "body" -> c.box.center
                "feet", "root" -> c.feet
                "eye" -> Vec3(c.feet.x, c.box.max.y - 0.1, c.feet.z)
                "center" -> c.center
                else -> return null
            }
            return SimFrame.fromYawPitch(o, c.yawDeg, 0.0)
        }

        private fun cell(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): ExprValue {
            val yaw = casterYawDeg
            val pos = Vec3(casterPosition.x, casterPosition.y, casterPosition.z)
            val casterFrame = if (faithfulPose) SimFrame.fromYawPitch(feet, yaw, 0.0) else SimFrame.fromYawPitch(pos, yaw, 0.0)
            val inst = scope.frames
            val frames = object : FrameLookup {
                override fun worldPose(frame: String, point: String?): SimFrame? = when {
                    // as PaperLiveValueSource.frames: `contact` exists only under a contact-carrying event
                    frame == "contact" -> binding?.contact?.let { c ->
                        com.astarworks.astera.core.domain.skillengine.frame.ContactBasis.frame(
                            Vec3(c.point.x, c.point.y, c.point.z), Vec3(c.normal.x, c.normal.y, c.normal.z), casterFrame.forward(),
                        )
                    }
                    (frame == "victim" || frame == "target") && faithfulPose -> victimOf(binding)?.let { v ->
                        val origin = when (point) {
                            null, "body" -> Vec3(v.feet.x, v.feet.y + 0.9, v.feet.z)
                            "feet", "root" -> v.feet
                            "eye" -> Vec3(v.feet.x, v.feet.y + 1.62, v.feet.z)
                            "center" -> v.center
                            else -> null
                        }
                        origin?.let { SimFrame.fromYawPitch(it, v.yawDeg, 0.0) }
                    }
                    frame == "caster" && faithfulPose -> when (point) {
                        null, "feet" -> casterFrame
                        "eye" -> SimFrame.fromYawPitch(eye, yaw, casterPitchDeg)
                        "look" -> SimFrame.fromYawPitch(feet, yaw, casterPitchDeg)
                        "center" -> SimFrame.fromYawPitch(Vec3(feet.x, feet.y + (eye.y - feet.y) / 2.0, feet.z), yaw, 0.0)
                        else -> casterFrame
                    }
                    // as PaperLiveValueSource.victimFrame: exists only when the event binds a victim, with the same point names (no world: caster stand-in pose)
                    frame == "victim" || frame == "target" ->
                        if (binding?.victim == null || point !in setOf(null, "body", "feet", "root", "eye", "center")) null else casterFrame
                    frame == "caster" -> casterFrame
                    frame == "victim" || frame == "target" -> victimFrame(binding, point)
                    else -> inst?.worldPose(frame, point)
                }
                override fun velocity(frame: String, point: String?) = if (frame == "caster") Vec3(0.0, 0.0, 0.0) else inst?.velocity(frame, point) ?: Vec3(0.0, 0.0, 0.0)
            }
            val world = EntityQueryExprHost(query, CastRef(cast.value, this@GatewayRecorder.caster, castWorld, castItem))
            val host = object : ExprHost {
                override fun aimPoint(caster: ExprValue.Ref, range: Double): Vec3 {
                    val from = if (faithfulPose) eye else pos
                    val d = SimFrame.fromYawPitch(from, yaw, if (faithfulPose) casterPitchDeg else 0.0).forward()
                    return Vec3(from.x + d.x * range, from.y + d.y * range, from.z + d.z * range)
                }
                override fun lookDirection(caster: ExprValue.Ref): Vec3 = SimFrame.fromYawPitch(pos, yaw, if (faithfulPose) casterPitchDeg else 0.0).forward()
                override fun stacks(entity: ExprValue.Ref, status: String): Double =
                    if (faithfulPose) gameplay.statusStacks(this@GatewayRecorder.caster, status).toDouble() else super.stacks(entity, status)
                override fun hasItem(entity: ExprValue.Ref, item: String): Boolean =
                    items.hasItem(CastRef(cast.value, this@GatewayRecorder.caster, castWorld, castItem), this@GatewayRecorder.caster, item)
                override fun nearestEnemyDist(from: Vec3): Double = world.nearestEnemyDist(from)
                override fun enemyInCone(from: Vec3, dir: Vec3, range: Double, halfAngleDeg: Double): Boolean = world.enemyInCone(from, dir, range, halfAngleDeg)
            }
            val vars = if (faithfulPose) V3Bindings.values(null, castTick = tick, eventI = binding?.index ?: 0, contact = contactBinding(binding), target = victimBinding(binding))
            else V3Bindings.values(null, castTick = tick, eventI = binding?.index ?: 0)
            return cells.eval(name, scope, vars, frames, host)
        }

        private val feet: Vec3 get() = Vec3(casterPosition.x, casterPosition.y - 1.0, casterPosition.z)
        private val eye: Vec3 get() = Vec3(feet.x, feet.y + 1.62, feet.z)

        /** `PaperLiveValueSource.casterVec` for a caster at [feet] looking along the recorder's yaw / pitch. */
        private fun faithfulVec(bare: String): Vector3d? {
            if (bare != "caster" && !bare.startsWith("caster.")) return null
            val hands = setOf("hand_r", "hand_l", "palm")
            val v = when (val field = bare.removePrefix("caster").removePrefix(".")) {
                "", "feet", "pos", "position", "look" -> feet
                "eye" -> eye
                "center" -> Vec3(feet.x, feet.y + (eye.y - feet.y) / 2.0, feet.z)
                "body" -> Vec3(feet.x, feet.y + 0.9, feet.z)
                "forward" -> SimFrame.fromYawPitch(feet, casterYawDeg, casterPitchDeg).forward()
                in hands -> {
                    val yawFrame = SimFrame.fromYawPitch(feet, casterYawDeg, 0.0)
                    val side = if (field == "hand_l") 1.0 else -1.0
                    val left = yawFrame.left()
                    val fwd = yawFrame.forward()
                    Vec3(feet.x + left.x * side * 0.35 + fwd.x * 0.45, feet.y + (eye.y - feet.y) - 0.55, feet.z + left.z * side * 0.35 + fwd.z * 0.45)
                }
                else -> throw IllegalArgumentException("caster field '$bare' is not one of feet, eye, center, body, look, forward, hand_r, hand_l, palm")
            }
            return Vector3d(v.x, v.y, v.z)
        }

        /** The hit victim of [binding] among the recorder's [victims] (the player binds the candidates `nearby` returned). */
        private fun victimOf(binding: EventBinding?): HitCandidate? = binding?.victim?.id?.let { id -> victims.firstOrNull { it.entityId == id } }

        private fun victimBinding(binding: EventBinding?): V3Bindings.Binding {
            val v = victimOf(binding) ?: return V3Bindings.Binding()
            val dir = SimFrame.fromYawPitch(v.feet, v.yawDeg, 0.0).forward()
            return V3Bindings.Binding(pos = v.feet, forward = dir, eye = Vec3(v.feet.x, v.feet.y + 1.62, v.feet.z))
        }

        private fun contactBinding(binding: EventBinding?): V3Bindings.Binding {
            val c = binding?.contact ?: return V3Bindings.Binding()
            val point = Vec3(c.point.x, c.point.y, c.point.z)
            return V3Bindings.Binding(pos = point, normal = Vec3(c.normal.x, c.normal.y, c.normal.z), point = point)
        }

        /** `PaperLiveValueSource.bindingVec`: `contact`, `event.victim` / `event.attacker`, `contact.*` and `target.*`. */
        private fun bindingVec(name: String, binding: EventBinding?): Vector3d? {
            if (name == "contact") return contactBinding(binding).point.let { Vector3d(it.x, it.y, it.z) }
            if (name == "event.victim" || name == "event.attacker") {
                val at = if (name == "event.victim") victimOf(binding)?.feet else if (binding?.attacker == caster) feet else null
                at ?: throw IllegalArgumentException("'$name' needs a hit event binding")
                return Vector3d(at.x, at.y, at.z)
            }
            val root = name.substringBefore('.', "")
            if (root != "contact" && root != "target") return null
            val b = if (root == "contact") contactBinding(binding) else victimBinding(binding)
            val v = when (val field = name.substringAfter('.')) {
                "pos" -> b.pos
                "forward" -> b.forward
                "eye" -> b.eye
                "normal" -> if (root == "contact") b.normal else throw IllegalArgumentException("'$name': only contact has a normal")
                "point" -> if (root == "contact") b.point else throw IllegalArgumentException("'$name': only contact has a point")
                else -> throw IllegalArgumentException("binding field '$field' of '$name' is not one of pos, forward, eye, normal, point")
            }
            return Vector3d(v.x, v.y, v.z)
        }

        override fun vec(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Vector3d {
            requireResolvable(name, "a vector")
            if (faithfulPose) {
                val bare = name.removePrefix("anchor:")
                faithfulVec(bare)?.let { return it }
                bindingVec(bare, binding)?.let { return it }
            }
            if (CellExprs.sourceOf(name) != null) {
                return when (val v = cell(cast, name, binding, scope)) {
                    is ExprValue.Vec3 -> Vector3d(v.value.x, v.value.y, v.value.z)
                    is ExprValue.Num -> Vector3d(v.value, 0.0, 0.0)
                    else -> throw IllegalArgumentException("live cell '$name' is not a vector")
                }
            }
            val victimName = name.removePrefix("anchor:")
            if (victimName == "event.victim" || victimName == "event.attacker") {
                // as PaperLiveValueSource.eventEntityVec: the bound entity's feet, and a named error without a hit binding
                val id = (if (victimName == "event.victim") binding?.victim?.id else binding?.attacker)
                    ?: throw IllegalArgumentException("'$victimName' needs a hit event binding")
                if (id == this@GatewayRecorder.caster) return Vector3d(casterPosition)
                val feet = victims.firstOrNull { it.entityId == id }?.feet ?: throw IllegalArgumentException("'$victimName': entity $id is not loaded")
                return Vector3d(feet.x, feet.y, feet.z)
            }
            if (victimName == "victim" || victimName == "target" || victimName.startsWith("victim.")) {
                val point = if (victimName.contains('.')) victimName.substringAfter('.') else null
                val o =(victimFrame(binding, point) ?: throw IllegalArgumentException("'$name' needs a hit event binding with a loaded victim")).origin
                return Vector3d(o.x, o.y, o.z)
            }
            // a direction cell (`caster.forward`) answers the caster's facing like Paper; every other field answers the caster position
            if (name.removePrefix("anchor:") == "caster.forward") {
                val yaw = Math.toRadians(casterYawDeg)
                return Vector3d(-Math.sin(yaw), 0.0, Math.cos(yaw))
            }
            return Vector3d(casterPosition)
        }

        override fun rot(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Quaterniond {
            requireResolvable(name, "a rotation")
            if (faithfulPose) {
                val bare = name.removePrefix("anchor:")
                if (bare == "caster" || bare.startsWith("caster.")) {
                    val pitched = bare == "caster.look" || bare == "caster.eye" || bare.removePrefix("caster.") in setOf("hand_r", "hand_l", "palm")
                    val q = SimFrame.fromYawPitch(feet, casterYawDeg, if (pitched) casterPitchDeg else 0.0).rotation
                    return Quaterniond(q.x, q.y, q.z, q.w)
                }
            }
            if (CellExprs.sourceOf(name) != null) {
                val v = cell(cast, name, binding, scope) as? ExprValue.Quat ?: throw IllegalArgumentException("live cell '$name' is not a rotation")
                return Quaterniond(v.value.x, v.value.y, v.value.z, v.value.w)
            }
            val victimName = name.removePrefix("anchor:")
            // as PaperLiveValueSource.rot: the contact basis exists only under a contact-carrying event; no silent identity
            if (victimName == "contact") {
                val c = binding?.contact ?: throw IllegalArgumentException("live cell '$name' is not resolvable as a rotation (no contact bound)")
                val q = com.astarworks.astera.core.domain.skillengine.frame.ContactBasis.frame(
                    Vec3(c.point.x, c.point.y, c.point.z), Vec3(c.normal.x, c.normal.y, c.normal.z), SimFrame.fromYawPitch(feet, casterYawDeg, 0.0).forward(),
                ).rotation
                return Quaterniond(q.x, q.y, q.z, q.w)
            }
            if (victimName == "victim" || victimName == "target" || victimName.startsWith("victim.")) {
                val point = if (victimName.contains('.')) victimName.substringAfter('.') else null
                val r = (victimFrame(binding, point) ?: throw IllegalArgumentException("'$name' needs a bound victim")).rotation
                return Quaterniond(r.x, r.y, r.z, r.w)
            }
            return Quaterniond()
        }

        override fun num(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Double {
            reads += name
            liveNums[name]?.let { return it }
            if (CellExprs.sourceOf(name) == null) return 0.0
            return when (val v = cell(cast, name, binding, scope)) {
                is ExprValue.Num -> v.value
                is ExprValue.Bool -> if (v.value) 1.0 else 0.0
                else -> throw IllegalArgumentException("live cell '$name' is not a number")
            }
        }

        override fun targets(cast: CastHandle, rule: TargetRule, binding: EventBinding?): TargetSet {
            // as the Paper resolver: `event.victim` is the hit event's own bound victim, nothing else; an unbound victim is a named error, never "every scripted victim"
            val ref = (rule as? TargetRule.Entity)?.ref ?: throw IllegalArgumentException("target rule $rule has no sim resolver here")
            if (ref != "event.victim") throw IllegalArgumentException("target rule '$ref' is resolved by the player, not the live source")
            val victim = binding?.victim ?: throw IllegalArgumentException("target 'event.victim' needs a hit event binding with a victim")
            return TargetSet(listOf(victim))
        }
    }
}

/** Number / vector / pose / string formatting of the oracle rows: fixed 6 decimals, no negative zero, no whitespace inside a value. */
internal object OracleFmt {
    fun f(d: Double): String {
        val r = if (kotlin.math.abs(d) < 5e-7) 0.0 else d
        return "%.6f".format(java.util.Locale.ROOT, r)
    }

    fun v(v: Vec3): String = "${f(v.x)},${f(v.y)},${f(v.z)}"

    /** Rotation with a canonical sign (q and -q are one rotation). */
    fun q(q: com.astarworks.astera.core.domain.geometry.math.Quaternion): String {
        val flip = q.w < -1e-9 || (kotlin.math.abs(q.w) <= 1e-9 && (q.x < -1e-9 || (kotlin.math.abs(q.x) <= 1e-9 && (q.y < -1e-9 || (kotlin.math.abs(q.y) <= 1e-9 && q.z < 0)))))
        val k = if (flip) -1.0 else 1.0
        return "${f(q.x * k)},${f(q.y * k)},${f(q.z * k)},${f(q.w * k)}"
    }

    fun pose(p: VisualPose): String =
        "pos=${v(p.frame.origin)} rot=${q(p.frame.rotation)} scale=${v(p.size)} bb=${p.billboard}" +
            (p.anchorFeet?.let { " feet=${v(it)}" } ?: "") + (p.teleportTicks?.let { " tp=$it" } ?: "")

    /** A resolved hit volume: kind and its dimensions (capsule a/b/radius, sphere radius, sector origin/forward/left/deg/range/height, box centre/half/rotation). */
    fun volume(vol: com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume): String = when (vol) {
        is com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume.Capsule -> "shape=capsule a=${v(vol.a)} b=${v(vol.b)} rad=${f(vol.radius)}"
        is com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume.Sphere -> "shape=sphere rad=${f(vol.radius)}"
        is com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume.Sector ->
            "shape=sector fwd=${v(vol.forward)} left=${v(vol.left)} deg=${f(vol.angleDeg)} range=${f(vol.range)} height=${f(vol.height)}"
        is com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume.Box ->
            "shape=box half=${v(vol.halfExtents)} rot=${q(com.astarworks.astera.core.domain.geometry.math.Quaternion(vol.rotation.x, vol.rotation.y, vol.rotation.z, vol.rotation.w))}"
    }

    fun view(v: DisplayView): String = "aud=${v.audience} lane=${v.lane} target=${v.target ?: "-"}"

    /** A string value on one token: whitespace and `=` escaped, null = `-`. */
    fun s(x: String?): String = x?.replace("%", "%25")?.replace(" ", "%20")?.replace("\n", "%0A")?.replace("=", "%3D")?.ifEmpty { "%00" } ?: "-"
}

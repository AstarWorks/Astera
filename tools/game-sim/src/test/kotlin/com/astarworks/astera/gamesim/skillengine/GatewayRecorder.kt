package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.CastTrace
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
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
    var victims: List<HitCandidate> = emptyList()
    var casterPosition: Vector3d = Vector3d(0.0, 1.0, 0.0)
    var casterYawDeg: Double = 0.0

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

    private fun seen(port: String, cast: CastRef) {
        refs += port to cast
    }

    private val log = ArrayList<RecordedCall>()
    private val ordinals = HashMap<Long, Int>()
    private var nextHandle = 0L

    val calls: List<RecordedCall> get() = log

    private fun add(verb: String, instance: String, payload: String) {
        log += RecordedCall(tick, verb, instance, payload)
    }

    private fun spawned(cast: CastRef, kind: String): VisualHandle {
        seen("visual.spawn.$kind", cast)
        val h = VisualHandle(nextHandle++)
        ordinals[h.id] = ordinals.size
        add("spawn", name(h), "kind=$kind")
        return h
    }

    private fun name(h: VisualHandle): String = "#" + (ordinals[h.id] ?: -1)

    /** The lock sink the platform's `PaperPlayerHooks` is: pitch / yaw go through `lockLook`, so a played lock shows as a `lock` call. */
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
                val ahead = com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 2.0)
                val vars = com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings.values(
                    null, stateTick = localTick, eventI = binding?.index ?: 0,
                    target = com.astarworks.astera.core.application.skillengine.expr.cel.V3Bindings.Binding(pos = ahead),
                )
                val frames = object : com.astarworks.astera.core.domain.skillengine.expr.FrameLookup {
                    override fun worldPose(frame: String, point: String?) = com.astarworks.astera.core.domain.geometry.math.Frame(
                        if (frame == "caster") com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0) else ahead,
                    )
                    override fun velocity(frame: String, point: String?) = com.astarworks.astera.core.domain.geometry.Vec3(0.0, 0.0, 0.0)
                }
                // like PaperPlayerHooks: a guard that cannot evaluate here (it needs a cast host, e.g. nearest_enemy_dist) answers null = a fault, false
                return try { (expr.eval(com.astarworks.astera.core.domain.skillengine.expr.ExprEnv(vars, frames, seed = seed)) as? com.astarworks.astera.core.domain.skillengine.expr.ExprValue.Bool)?.value } catch (e: RuntimeException) { null }
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
        override fun spawnMesh(cast: CastRef, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook, view: DisplayView, glow: String?) = spawned(cast, "item")

        override fun spawnSkinBone(cast: CastRef, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) = spawned(cast, "skin_bone")

        override fun spawnPlate(cast: CastRef, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) = spawned(cast, "sprite")

        override fun spawnBlock(cast: CastRef, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?) = spawned(cast, "block")

        override fun spawnText(cast: CastRef, text: String, backgroundArgbHex: String, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) = spawned(cast, "text")

        override fun spawnTrail(cast: CastRef, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = spawned(cast, "ribbon")

        override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) =
            add("write", name(handle), "dur=$interpolationTicks")

        override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) = add("trail", name(handle), "")

        override fun remove(handle: VisualHandle) = add("remove", name(handle), "")

        override fun sprite(sprite: String): SpriteInfo = SpriteInfo(1, 32)

        override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) = Unit

        override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) = Unit

        override fun setTrailWidth(handle: VisualHandle, widthFrac: Double) = add("trail_width", name(handle), "w=%.3f".format(widthFrac))
    }

    val particles: ISkillParticleGateway = object : ISkillParticleGateway {
        override fun emit(cast: CastRef, burst: ParticleBurst) {
            seen("particles.emit", cast)
            add("particle", "-", "type=${burst.particle}" + (burst.count?.let { " count=$it" } ?: ""))
        }
    }

    val sounds: ISkillSoundGateway = object : ISkillSoundGateway {
        override fun play(cast: CastRef, sound: String, at: Vec3, category: String, volume: Double, pitch: Double) {
            seen("sounds.play", cast)
            add("sound", "-", "sound=$sound cat=$category vol=${RecordedCall.num(volume)} pitch=${RecordedCall.num(pitch)}")
        }
    }

    val gameplay: ISkillGameplayGateway = object : ISkillGameplayGateway {
        override fun damage(skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean, ignoreArmor: Boolean) =
            add("damage", victim.toString(), components.joinToString(" ") { "${it.kind}=${RecordedCall.num(it.amount)}" })

        override fun statusStacks(entity: UUID, statusId: String): Int = 0

        override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) =
            add("status", victim.toString(), "status=$statusId stacks=$stacks for=$durationTicks")

        override fun rollChance(chance: Double): Boolean = true

        override fun removeStatus(victim: UUID, statusId: String) = add("effect", victim.toString(), "remove_status=$statusId")

        override fun cleanse(victim: UUID, negativeOnly: Boolean) = add("effect", victim.toString(), "cleanse")

        override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) = add("effect", victim.toString(), "ignite=$durationTicks")

        override fun silence(caster: UUID, victim: UUID, durationTicks: Int) = add("effect", victim.toString(), "silence=$durationTicks")

        override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) =
            add("effect", victim.toString(), "attribute=$attribute")

        override fun restoreResource(victim: UUID, resource: String, amount: Double) = add("effect", victim.toString(), "resource=$resource")

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

        override fun stopSound(cast: CastRef, viewer: UUID, sound: String) {
            seen("gameplay.stopSound", cast)
            add("effect", viewer.toString(), "stop_sound=$sound")
        }

        override fun playBodyClip(cast: CastRef, caster: UUID, anim: String, speed: Double) {
            seen("gameplay.playBodyClip", cast)
            add("effect", caster.toString(), "body=$anim speed=${RecordedCall.num(speed)}")
        }

        override fun stopBodyClip(cast: CastRef, caster: UUID, anim: String) {
            seen("gameplay.stopBodyClip", cast)
            add("effect", caster.toString(), "body_stop=$anim")
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
            return victims.filter { it.entityId != excluding }
        }

        override fun rootOf(cast: CastRef, entity: UUID): EntityRoot? {
            seen("query.rootOf", cast)
            return if (entity == caster) EntityRoot(Vec3(casterPosition.x, casterPosition.y - 1.0, casterPosition.z), casterYawDeg, 0.0, 1.62, 1.8) else null
        }

        override fun raycastBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit? {
            seen("query.raycastBlock", cast)
            return null
        }

        override fun healthRatio(cast: CastRef, entity: UUID): Double {
            seen("query.healthRatio", cast)
            return 1.0
        }
    }

    /** Caster motion (R40): `teleport` moves [casterPosition] (feet + 1) so the next `rootOf` sees it; [wall] answers `probeBlock`. */
    var wall: (Vec3, Vec3) -> BlockHit? = { _, _ -> null }
    var velocity: Vec3 = Vec3.ZERO

    val movement: ISkillMovementGateway = object : ISkillMovementGateway {
        override fun teleport(cast: CastRef, entity: UUID, pos: Vec3, yaw: Double?, pitch: Double?) {
            seen("movement.teleport", cast)
            if (entity == this@GatewayRecorder.caster) casterPosition = Vector3d(pos.x, pos.y + 1.0, pos.z)
            add("move", "-", "to=${RecordedCall.num(pos.x)},${RecordedCall.num(pos.y)},${RecordedCall.num(pos.z)}")
        }

        override fun velocity(cast: CastRef, entity: UUID): Vec3 {
            seen("movement.velocity", cast)
            return velocity
        }

        override fun addVelocity(cast: CastRef, entity: UUID, v: Vec3) {
            seen("movement.addVelocity", cast)
            velocity = velocity + v
            add("move", "-", "add_velocity")
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
            add("move", "-", "face")
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
        override fun vec(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Vector3d = Vector3d(casterPosition)

        override fun rot(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Quaterniond = Quaterniond()

        override fun num(cast: CastHandle, name: String, binding: EventBinding?, scope: RowScope): Double {
            reads += name
            return liveNums[name] ?: 0.0
        }

        override fun targets(cast: CastHandle, rule: TargetRule, binding: EventBinding?): TargetSet =
            TargetSet(victims.map { EntityRef(it.entityId) })
    }
}

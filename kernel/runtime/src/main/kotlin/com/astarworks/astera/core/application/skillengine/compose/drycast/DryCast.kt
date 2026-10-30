package com.astarworks.astera.core.application.skillengine.compose.drycast

import com.astarworks.astera.core.application.skillengine.runtime.CastRequest
import com.astarworks.astera.core.domain.skillengine.table.CancelScope
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.CastTrace
import com.astarworks.astera.core.application.skillengine.runtime.player.TablePlayer
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillGameplayGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillParticleGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillSoundGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillWorldGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook
import com.astarworks.astera.core.application.skillengine.runtime.port.ParticleBurst
import com.astarworks.astera.core.application.skillengine.runtime.port.SkillDamage
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.EntityRoot
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.hit.HitBox
import com.astarworks.astera.core.domain.skillengine.hit.HitCandidate
import com.astarworks.astera.core.domain.skillengine.model.CasterPose
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.model.drycast.DisplaySample
import com.astarworks.astera.core.domain.skillengine.model.drycast.DryCastTrace
import com.astarworks.astera.core.domain.skillengine.model.drycast.ParticleSample
import com.astarworks.astera.core.domain.skillengine.table.CompiledSkill
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.op.TrailBand
import com.astarworks.astera.core.domain.skillengine.op.TrailEdge
import com.astarworks.astera.core.domain.skillengine.op.TrailRim
import com.astarworks.astera.core.domain.skillengine.table.EventPath
import com.astarworks.astera.core.domain.skillengine.table.ExitReason
import com.astarworks.astera.core.domain.skillengine.table.StatePath
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Plays a compiled skill once through the real table player against recording gateways, with the caster standing at the origin looking along
 * [CasterPose], and returns what it put on the caster's screen: every particle burst and every display spawn / write / removal, each attributed to the
 * YAML row that issued it. No victims unless [victims] are given; chances roll true and `has_item` is true, so the worst case plays. A player fault
 * (a row that cannot run in the dry cast) is not an error here: the check only reads what was drawn.
 *
 * Load-time check 16 (engine-gaps-v2 section 1) and the game-sim hit poses call this one implementation.
 */
public object DryCast {
    private val CASTER: UUID = UUID(0L, 1L)

    public fun run(skill: CompiledSkill, pose: CasterPose, config: EngineConfig, ctx: ClutterContext, victims: List<HitCandidate> = emptyList()): DryCastTrace {
        val rec = Recorder(skill, ctx, victims)
        val source = StaticLiveValueSource(config.casterEye.eyeHeight, pose, CASTER, victims, rec.query)
        val player = TablePlayer(rec.visual, rec.particles, rec.sounds, rec.gameplay, rec.query, source, rec, rec.movement, rec.items, rec.world)
        player.config = config
        player.hooks = DryCastHooks(rec.query, CASTER)
        val request = CastRequest(
            skill = skill, caster = CASTER, world = WorldId("dry-cast"), casterFeet = Vec3(0.0, 0.0, 0.0),
            casterYawDeg = pose.yawDeg, casterPitchDeg = pose.pitchDeg, seed = 0L, startTick = 0,
            heldItem = skill.def.item?.takeIf { it != "any" },
        )
        val last = skill.table.bounds.path.max + skill.table.bounds.tail + 2
        val cast = when (val s = runCatching { player.start(request, skill) }.getOrNull()) {
            is Result.Success -> s.value
            else -> return DryCastTrace(emptyList(), emptyList(), 0)
        }
        var played = 0
        for (now in 0..last) {
            rec.tick = now
            if (runCatching { player.tick(now) }.isFailure) break
            played = now
        }
        runCatching { player.cancel(cast, CancelScope.Cast) }
        return DryCastTrace(rec.particleSamples, rec.displaySamples, played)
    }

    private class Recorder(skill: CompiledSkill, private val ctx: ClutterContext, private val victims: List<HitCandidate>) : CastTrace {
        var tick: Int = 0
        val particleSamples = ArrayList<ParticleSample>()
        val displaySamples = ArrayList<DisplaySample>()

        private val srcByInstance: Map<String, SrcPath> = HashMap<String, SrcPath>().also { m ->
            val t = skill.table
            (t.states.flatMap { it.rows } + t.subTables.values.flatMap { it.rows } + t.worldRows).forEach { m.putIfAbsent(it.instance.text, it.src) }
        }
        private var rowOp: String? = null
        private val src: SrcPath? get() = rowOp?.let { srcByInstance[it] ?: srcByInstance[it.replace(Regex("\\[\\d+]"), "")] }

        override fun enter(cast: CastHandle, state: StatePath, tick: Int) {}
        override fun exit(cast: CastHandle, state: StatePath, reason: ExitReason, tick: Int) {}
        override fun event(cast: CastHandle, path: EventPath, tick: Int, index: Int?) {}
        override fun call(cast: CastHandle, callId: InstanceId, tick: Int) {}
        override fun row(cast: CastHandle, op: InstanceId, tick: Int) { rowOp = op.text }

        private var nextHandle = 0L
        private val handleSrc = HashMap<Long, SrcPath?>()
        private val handleKind = HashMap<Long, String>()

        private fun spawn(kind: String, pose: VisualPose?): VisualHandle {
            val h = VisualHandle(nextHandle++)
            handleSrc[h.id] = src
            handleKind[h.id] = kind
            displaySamples += DisplaySample(tick, h.id, kind, pose?.frame?.origin, pose?.size, alive = true, spawn = true, src = src)
            return h
        }

        val visual: ISkillVisualGateway = object : ISkillVisualGateway {
            override fun spawnMesh(cast: CastRef, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook, view: DisplayView, glow: String?) = spawn("item", pose)
            override fun spawnSkinBone(cast: CastRef, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) = spawn("skin_bone", pose)
            override fun spawnPlate(cast: CastRef, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) = spawn("sprite", pose)
            override fun spawnBlock(cast: CastRef, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?) = spawn("block", pose)
            override fun spawnText(cast: CastRef, text: String, backgroundArgbHex: String, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) = spawn("text", pose)
            override fun spawnTrail(cast: CastRef, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = spawn("ribbon", null)
            override fun spawnLine(cast: CastRef, vertices: List<Vec3>, thickness: Double, colorHex: String, alpha: Double) = spawn("ribbon", null)
            override fun updateLine(handle: VisualHandle, vertices: List<Vec3>) {}
            override fun spawnScreenVeil(cast: CastRef, viewer: UUID, sprite: String, distance: Double, scale: Double, colorHex: String?, maxScreenFraction: Double, ticks: Int) = spawn("veil", null)
            override fun updateScreenVeil(handle: VisualHandle, age: Int) {}
            override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) {
                displaySamples += DisplaySample(tick, handle.id, handleKind[handle.id] ?: "?", pose.frame.origin, pose.size, alive = true, spawn = false, src = handleSrc[handle.id])
            }
            override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) {}
            override fun remove(handle: VisualHandle) {
                displaySamples += DisplaySample(tick, handle.id, handleKind[handle.id] ?: "?", null, null, alive = false, spawn = false, src = handleSrc[handle.id])
            }
            override fun sprite(sprite: String): SpriteInfo = SpriteInfo(1, 32)
            override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) {}
            override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) {}
            override fun setTrailWidth(handle: VisualHandle, widthFrac: Double) {}
        }

        val particles: ISkillParticleGateway = object : ISkillParticleGateway {
            override fun emit(cast: CastRef, burst: ParticleBurst) {
                particleSamples += ParticleSample(tick, burst.particle, burst.at, burst.count, burst.dataSize, burst.offset, burst.speed, burst.look, src)
            }
        }

        val sounds: ISkillSoundGateway = object : ISkillSoundGateway {
            override fun play(cast: CastRef, sound: String, at: Vec3, category: String, volume: Double, pitch: Double) {}
        }

        val gameplay: ISkillGameplayGateway = object : ISkillGameplayGateway {
            override fun damage(skillId: String, caster: UUID, victim: UUID, components: List<SkillDamage>, hitPoint: Vec3, ignoreInvulnerability: Boolean, ignoreArmor: Boolean) = false
            override fun statusStacks(entity: UUID, statusId: String) = 0
            override fun applyStatus(caster: UUID, victim: UUID, statusId: String, stacks: Int, durationTicks: Int) {}
            override fun rollChance(chance: Double) = true
            override fun removeStatus(victim: UUID, statusId: String) {}
            override fun cleanse(victim: UUID, negativeOnly: Boolean) {}
            override fun ignite(caster: UUID, victim: UUID, durationTicks: Int) {}
            override fun silence(caster: UUID, victim: UUID, durationTicks: Int) {}
            override fun modifyAttribute(caster: UUID, victim: UUID, attribute: String, operation: String, amount: Double, durationTicks: Int?) {}
            override fun restoreResource(victim: UUID, resource: String, amount: Double) {}
            override fun impulse(victim: UUID, vector: Vec3) {}
            override fun freezeEntities(entities: List<UUID>, ticks: Int) {}
            override fun cancelIncomingHit(cast: CastRef, attacker: UUID?) {}
            override fun consumeStatus(cast: CastRef, entity: UUID, status: String) {}
            override fun cameraShake(cast: CastRef, viewer: UUID, intensity: Double, ticks: Int, from: Vec3?) {}
            override fun screenFlash(cast: CastRef, viewer: UUID, overlay: String, colorRgb: Int?, fadeInTicks: Int, stayTicks: Int, fadeOutTicks: Int) {}
            override fun stopSound(cast: CastRef, viewer: UUID, sound: String) {}
            override fun playBodyClip(cast: CastRef, target: UUID, anim: String, speed: Double, holds: List<com.astarworks.astera.core.domain.animation.skinrig.SkinRigHold>) {}
            override fun isPlayer(entity: UUID) = entity == CASTER
            override fun bodyClip(anim: String) = ctx.bodyClip(anim)
            override fun bodySkeleton() = ctx.bodySkeleton
            override fun stopBodyClip(cast: CastRef, target: UUID, anim: String) {}
            override fun setHeldModel(cast: CastRef, caster: UUID, model: String) {}
            override fun clearHeldModel(cast: CastRef, caster: UUID) {}
            override fun hurt(cast: CastRef, target: UUID, from: Vec3) {}
        }

        val query: ISkillEntityQuery = object : ISkillEntityQuery {
            override fun nearby(cast: CastRef, point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> = victims.filter { it.entityId != excluding }
            override fun bodiesNear(cast: CastRef, point: Vec3, radius: Double, excluding: UUID?): List<HitBox> = victims.filter { it.entityId != excluding }.map { it.box }
            override fun rootOf(cast: CastRef, entity: UUID): EntityRoot? {
                victims.firstOrNull { it.entityId == entity }?.let { return EntityRoot(it.feet, it.yawDeg, 0.0, it.eyeHeight ?: 0.0, 1.8) }
                return null
            }
            override fun raycastBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit? = null
            override fun isAlly(cast: CastRef, a: UUID, b: UUID) = a == b
        }

        val movement: ISkillMovementGateway = object : ISkillMovementGateway {
            override fun teleport(cast: CastRef, entity: UUID, pos: Vec3, yaw: Double?, pitch: Double?) {}
            override fun velocity(cast: CastRef, entity: UUID) = Vec3.ZERO
            override fun addVelocity(cast: CastRef, entity: UUID, v: Vec3) {}
            override fun probeBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit? = null
            override fun invulnerable(cast: CastRef, entity: UUID, ticks: Int) {}
            override fun faceMovement(cast: CastRef, entity: UUID, dir: Vec3) {}
        }

        val items: ISkillItemGateway = object : ISkillItemGateway {
            override fun consume(cast: CastRef, holder: UUID, item: String, count: Int) = true
            override fun cooldown(cast: CastRef, skill: String, ticks: Int) {}
            override fun hasItem(cast: CastRef, holder: UUID, item: String) = true
            override fun applyGate(cast: CastRef, skill: String, cooldown: Boolean, cost: Boolean) {}
        }

        val world: ISkillWorldGateway = object : ISkillWorldGateway {
            override fun breakArea(cast: CastRef, spec: com.astarworks.astera.core.domain.skillengine.world.BreakAreaSpec, aim: com.astarworks.astera.core.domain.skillengine.world.WorldAim) {}
            override fun placeShape(cast: CastRef, spec: com.astarworks.astera.core.domain.skillengine.world.PlaceShapeSpec, aim: com.astarworks.astera.core.domain.skillengine.world.WorldAim) {}
            override fun explode(cast: CastRef, at: Vec3, power: Double, breakBlocks: Boolean, fire: Boolean) {}
            override fun lightning(cast: CastRef, at: Vec3, visualOnly: Boolean) {}
            override fun tick() {}
            override fun revertCast(cast: CastRef) {}
            override fun revertAll() {}
        }
    }
}

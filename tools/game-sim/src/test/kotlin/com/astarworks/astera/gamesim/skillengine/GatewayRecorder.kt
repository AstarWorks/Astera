package com.astarworks.astera.gamesim.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.player.EventBinding
import com.astarworks.astera.core.application.skillengine.runtime.player.CastHandle
import com.astarworks.astera.core.application.skillengine.runtime.player.LiveValueSource
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
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.UUID

/**
 * In-memory gateways for the table player: every call becomes a [RecordedCall] stamped with [tick]. The harness sets [tick]
 * before each `player.tick(now)`. Handles are numbered in spawn order; the call's `instance` is `#<ordinal>`.
 *
 * No world exists: [victims] is what `nearby` returns (default none), so target-dependent rows (`damage`, `status`, `effect`)
 * produce no calls unless a case scripts victims. Live values resolve to fixed stand-ins ([casterPosition], identity
 * rotation); table rows never depend on their value (positions are never compared).
 */
class GatewayRecorder(val caster: UUID = UUID(0L, 1L)) {
    var tick: Int = 0
    var victims: List<HitCandidate> = emptyList()
    var casterPosition: Vector3d = Vector3d(0.0, 1.0, 0.0)

    private val log = ArrayList<RecordedCall>()
    private val ordinals = HashMap<Long, Int>()
    private var nextHandle = 0L

    val calls: List<RecordedCall> get() = log

    private fun add(verb: String, instance: String, payload: String) {
        log += RecordedCall(tick, verb, instance, payload)
    }

    private fun spawned(kind: String): VisualHandle {
        val h = VisualHandle(nextHandle++)
        ordinals[h.id] = ordinals.size
        add("spawn", name(h), "kind=$kind")
        return h
    }

    private fun name(h: VisualHandle): String = "#" + (ordinals[h.id] ?: -1)

    val visual: ISkillVisualGateway = object : ISkillVisualGateway {
        override fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int?, look: MeshLook, view: DisplayView, glow: String?) = spawned("item")

        override fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) = spawned("skin_bone")

        override fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView, glow: String?) = spawned("sprite")

        override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView, glow: String?) = spawned("block")

        override fun spawnText(castOwner: UUID, text: String, backgroundHex: String, backgroundAlpha: Double, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) = spawned("text")

        override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = spawned("ribbon")

        override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) =
            add("write", name(handle), "dur=$interpolationTicks")

        override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) = add("trail", name(handle), "")

        override fun remove(handle: VisualHandle) = add("remove", name(handle), "")

        override fun sprite(sprite: String): SpriteInfo = SpriteInfo(1, 32)

        override fun setTrailLook(handle: VisualHandle, fadePower: Double, style: String) = Unit

        override fun setTrailShape(handle: VisualHandle, bands: List<TrailBand>, edge: TrailEdge?, rim: TrailRim?, stepDeg: Double, doubleSided: Boolean) = Unit
    }

    val particles: ISkillParticleGateway = object : ISkillParticleGateway {
        override fun emit(burst: ParticleBurst) {
            add("particle", "-", "type=${burst.particle}" + (burst.count?.let { " count=$it" } ?: ""))
        }
    }

    val sounds: ISkillSoundGateway = object : ISkillSoundGateway {
        override fun play(sound: String, at: Vec3, category: String, volume: Double, pitch: Double) {
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

        override fun impulse(victim: UUID, vector: Vec3) = add("effect", victim.toString(), "impulse")

        override fun freezeEntities(entities: List<UUID>, ticks: Int) = entities.forEach { add("effect", it.toString(), "freeze=$ticks") }

        override fun lockLook(caster: UUID, pitch: Boolean, yaw: Boolean, untilTick: Int) = add("lock", "-", "pitch=$pitch yaw=$yaw until=$untilTick")

        override fun unlockLook(caster: UUID) = add("unlock", "-", "")

        override fun consumeStatus(cast: CastRef, entity: UUID, status: String) = add("effect", entity.toString(), "consume_status=$status")

        override fun cameraShake(cast: CastRef, viewer: UUID, intensity: Double, ticks: Int, from: Vec3?) =
            add("effect", viewer.toString(), "camera_shake=${RecordedCall.num(intensity)} ticks=$ticks from=${if (from == null) "none" else "hit"}")

        override fun stopSound(cast: CastRef, viewer: UUID, sound: String) = add("effect", viewer.toString(), "stop_sound=$sound")

        override fun playBodyClip(cast: CastRef, caster: UUID, anim: String, speed: Double) =
            add("effect", caster.toString(), "body=$anim speed=${RecordedCall.num(speed)}")

        override fun stopBodyClip(cast: CastRef, caster: UUID, anim: String) = add("effect", caster.toString(), "body_stop=$anim")
    }

    val query: ISkillEntityQuery = object : ISkillEntityQuery {
        override fun nearby(point: Vec3, radius: Double, excluding: UUID?): List<HitCandidate> = victims.filter { it.entityId != excluding }

        override fun rootOf(entity: UUID): EntityRoot? =
            if (entity == caster) EntityRoot(Vec3(casterPosition.x, casterPosition.y - 1.0, casterPosition.z), 0.0, 0.0, 1.62, 1.8) else null

        override fun raycastBlock(from: Vec3, to: Vec3): BlockHit? = null

        override fun healthRatio(entity: UUID): Double = 1.0
    }

    val live: LiveValueSource = object : LiveValueSource {
        override fun vec(cast: CastHandle, name: String, binding: EventBinding?): Vector3d = Vector3d(casterPosition)

        override fun rot(cast: CastHandle, name: String, binding: EventBinding?): Quaterniond = Quaterniond()

        override fun num(cast: CastHandle, name: String, binding: EventBinding?): Double = casterPosition.x

        override fun targets(cast: CastHandle, rule: TargetRule, binding: EventBinding?): TargetSet =
            TargetSet(victims.map { EntityRef(it.entityId) })
    }
}

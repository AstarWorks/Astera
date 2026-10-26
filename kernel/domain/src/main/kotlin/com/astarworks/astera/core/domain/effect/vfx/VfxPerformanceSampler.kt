package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.skinrig.RigFrame
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/**
 * What a performance looks like at one skill tick, in CASTER-LOCAL space: origin at the feet, `+x` right, `+y` up, `+z`
 * forward, the cast-time facing fixed (ADR-0276). This is the data `skill-lint` reads (`build/skill-lint/<skill>/plan.json`);
 * the sampler is the same math [VfxBodyPlanner] sends to clients, evaluated without any entity.
 *
 * The VFX frame has `+x` = left (a Minecraft local frame); output vectors are mirrored to `+x` = right and quaternions are
 * conjugated by that mirror (`(x, y, z, w) -> (x, -y, -z, w)`).
 */
public object VfxPerformanceSampler {

    public class PoseOut(public val pos: Vec3, public val rot: Quaternion, public val scale: Vec3 = Vec3(1.0, 1.0, 1.0))

    public class WeaponOut(public val pos: Vec3, public val rot: Quaternion, public val tip: Vec3, public val edgeNormal: Vec3)

    public class Tick(public val tick: Int, public val bones: Map<String, PoseOut>, public val weapon: WeaponOut?)

    /**
     * One entry per effect tick `0..durationTicks` (shifted by [startTick] to skill ticks). The state shown at effect tick `e` is
     * the last rig tick whose chunk has arrived by `e` (hit-stop freezes included).
     */
    public fun sample(
        def: VfxDefinition,
        body: VfxBodyContext?,
        freezes: List<VfxFreeze> = emptyList(),
        scale: Double = 1.0,
        startTick: Int = 0,
    ): List<Tick> {
        val rig = def.rigs.firstOrNull { it.weapon != null } ?: error("effect '${def.id}' has no weapon rig")
        val track = body?.let { VfxBodyTrack(it) }
        val motion = VfxRigMotion(rig, Quaternion.IDENTITY, scale, freezes, def.sampleTicks, track)
        val last = def.durationTicks - motion.base
        val tipLocal = rig.weapon!!.tip ?: Vec3(0.0, 1.0, 0.0)
        return (0..def.durationTicks).map { e ->
            var l = 0
            while (l < last && motion.effectTickEnd(l + 1) <= e) l++
            val s = l.toDouble()
            val frame = track?.at(l)?.frame ?: RigFrame.REST
            val transforms = track?.at(l)?.transforms ?: SkinRigTransforms.compute(RigFrame.REST, false)
            val bones = LinkedHashMap<String, PoseOut>()
            for (bone in SkinRigBone.entries) {
                val t = transforms.bones.getValue(bone)
                bones[bone.yamlName] = out(Vec3(t.translation.x, t.translation.y, t.translation.z) * scale, VfxBodyTrack.quat(t.rotation), Vec3(t.scale.x, t.scale.y, t.scale.z) * scale)
            }
            val hips = SkinRigTransforms.hips(frame)
            bones["hips"] = out(hips.joint * scale, VfxBodyTrack.quat(hips.rotation))
            val arm = SkinRigTransforms.rightArm(frame, body?.slim ?: false)
            val armRot = VfxBodyTrack.quat(arm.rotation)
            bones["right_hand"] = out((arm.joint + armRot.rotate(SkinRigTransforms.HELD_GRIP_OFFSET)) * scale, armRot)
            val weapon = WeaponOut(
                pos = mirror(motion.originWorldAt(s)),
                rot = mirror(motion.weaponRotationAt(s)),
                tip = mirror(motion.pointAt(tipLocal, s)),
                edgeNormal = mirror(motion.worldRotationAt(s).rotate(rig.weapon.edgeNormal)),
            )
            Tick(e + startTick, bones, weapon)
        }
    }

    private fun out(pos: Vec3, rot: Quaternion, size: Vec3 = Vec3(1.0, 1.0, 1.0)): PoseOut = PoseOut(mirror(pos), mirror(rot), size)

    private fun mirror(v: Vec3): Vec3 = Vec3(-v.x, v.y, v.z)

    private fun mirror(q: Quaternion): Quaternion = Quaternion(q.x, -q.y, -q.z, q.w)
}

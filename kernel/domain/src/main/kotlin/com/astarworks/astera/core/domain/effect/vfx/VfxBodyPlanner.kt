package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigTransforms
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion

/**
 * Plans one skill PERFORMANCE (ADR-0276): the six skin-rig bones of [VfxBodyTrack] and ONE weapon display (the caster's
 * real main-hand stack) as ordinary [VfxElementPlan]s on one clock, in the cast-time frame.
 *
 * `weapon(t) = hand(t) . grip . wrist(t)`: the weapon hangs from the right arm of the body at rig tick `t` (grip = the
 * vanilla held offset plus [VfxRig.pivot]), and the rig's `swing` keys are the wrist. The same [VfxRigMotion] drives trails,
 * emitters and boards, so every effect that follows the blade follows the hand.
 *
 * Pose-to-pose grammar: a state is sent once per rig tick, and only when it differs from the previous one (a hold emits
 * nothing). A tick that reaches a `step` key (body channel or wrist) is a CUT: every element jumps with interpolation 0 in
 * the same server tick, and the runtime flushes those ticks as one bundle per viewer.
 */
public object VfxBodyPlanner {

    private const val EPS: Double = 1e-6

    private class Step(val rigTick: Int, val tick: Int, val interpolation: Int)

    private class Pose(val translation: Vec3, val rotation: Quaternion, val scale: Vec3) {
        fun transform(): DisplayTransform = DisplayTransform(translation, rotation, scale)
        fun differs(o: Pose): Boolean =
            (translation - o.translation).lengthSquared() > EPS * EPS || rotation.angleTo(o.rotation) > EPS || (scale - o.scale).lengthSquared() > EPS * EPS
    }

    public fun plan(def: VfxDefinition, rig: VfxRig, motion: VfxRigMotion, track: VfxBodyTrack?): List<VfxElementPlan> {
        val spawn = rig.startTick
        val despawn = minOf(def.durationTicks, rig.endTick ?: def.durationTicks)
        if (track != null) {
            require(track.lengthTicks == def.durationTicks) {
                "body animation '${track.body.animation.id.value}' is ${track.lengthTicks} ticks but the effect lasts " +
                    "${def.durationTicks}: a performance body must cover exactly its duration"
            }
        }
        val last = def.durationTicks - motion.base
        val schedule = schedule(motion, last, despawn) { track?.isSnap(it) == true || motion.isSwingStep(it) }

        val out = ArrayList<VfxElementPlan>()
        if (track != null) {
            for (bone in SkinRigBone.entries) {
                out += element(
                    id = "${rig.id}.body.${bone.yamlName}",
                    kind = VfxElementKind.SkinBone(bone, track.body.slim),
                    spawn = spawn, despawn = despawn, last = last, schedule = schedule,
                ) { l ->
                    val t = track.at(l).transforms.bones.getValue(bone)
                    Pose(
                        motion.anchorRot.rotate(Vec3(t.translation.x, t.translation.y, t.translation.z) * motion.scale),
                        motion.anchorRot * Quaternion(t.rotation.x, t.rotation.y, t.rotation.z, t.rotation.w),
                        t.scale * motion.scale,
                    )
                }
            }
        }
        if (rig.weapon != null) {
            val size = SkinRigTransforms.PLAYER_SCALE * motion.scale
            out += element(
                id = "${rig.id}.weapon",
                kind = VfxElementKind.MainHandWeapon,
                spawn = spawn, despawn = despawn, last = last, schedule = schedule,
            ) { l ->
                val s = l.toDouble()
                Pose(motion.originWorldAt(s), motion.anchorRot * motion.weaponRotationAt(s), Vec3(size, size, size))
            }.withModelSwaps(rig.weapon.models, motion, last, despawn)
        }
        return out
    }

    /** Which rig ticks are sent when: normal ticks one tick ahead with interpolation 1, cuts on arrival with 0. */
    private fun schedule(motion: VfxRigMotion, last: Int, despawn: Int, isCut: (Int) -> Boolean): List<Step> {
        val out = ArrayList<Step>()
        var lastTick = motion.base - 1
        for (l in 1..last) {
            val cut = isCut(l)
            var tick = if (cut) motion.effectTickEnd(l) else motion.effectTickStart(l - 1)
            var interpolation = if (cut) 0 else 1
            if (tick <= lastTick) {
                // A normal move would start in the tick a cut lands: it becomes a cut itself, on its own arrival tick.
                tick = motion.effectTickEnd(l)
                interpolation = 0
            }
            if (tick >= despawn) break
            out += Step(l, tick, interpolation)
            lastTick = tick
        }
        return out
    }

    private fun element(
        id: String,
        kind: VfxElementKind,
        spawn: Int,
        despawn: Int,
        last: Int,
        schedule: List<Step>,
        pose: (Int) -> Pose,
    ): VfxElementPlan {
        var initial = pose(0)
        var previous = initial
        val updates = ArrayList<VfxUpdate>()
        for (step in schedule) {
            val next = pose(step.rigTick)
            if (step.tick <= spawn) {
                // A step that lands at or before this element's spawn is part of its initial pose (a ghost may spawn mid-clip).
                initial = next
                previous = next
                continue
            }
            if (!next.differs(previous)) continue
            updates += VfxUpdate(step.tick, next.transform(), step.interpolation)
            previous = next
        }
        return VfxElementPlan(
            elementId = id,
            slot = 0,
            kind = kind,
            brightness = null,
            glowArgb = null,
            billboard = DisplayBillboard.FIXED,
            viewRange = null,
            spawnTick = spawn,
            despawnTick = despawn,
            initial = initial.transform(),
            updates = updates,
            particles = emptyList(),
        )
    }

    /**
     * Adds the weapon's `item_model` step keys (ADR-0282) as transform-free updates on the arrival tick of the key's rig tick
     * (key 0 lands on the spawn tick). Same display, same plan: never a second weapon.
     */
    private fun VfxElementPlan.withModelSwaps(models: List<VfxWeaponModelKey>, motion: VfxRigMotion, last: Int, despawn: Int): VfxElementPlan {
        if (models.isEmpty()) return this
        val swaps = models.filter { it.t <= last }.mapNotNull { key ->
            val tick = if (key.t == 0) spawnTick else motion.effectTickEnd(key.t)
            if (tick >= despawn) null
            else VfxUpdate(tick, initial, 1, keepTransform = true, itemModel = key.model)
        }
        return copy(updates = (updates + swaps).sortedBy { it.tick })
    }

    /**
     * ADR-0284: the six bones of [ghost] as visual-only [VfxElementPlan]s. They follow the performance clock of [motion] but sample
     * the body [VfxGhostBody.delayTicks] rig ticks late, shifted by [VfxGhostBody.offset] in the effect frame. No weapon, no lease.
     */
    public fun planGhost(def: VfxDefinition, ghost: VfxGhostBody, motion: VfxRigMotion, track: VfxBodyTrack): List<VfxElementPlan> {
        val spawn = ghost.startTick
        val despawn = minOf(def.durationTicks, ghost.endTick ?: def.durationTicks)
        val last = def.durationTicks - motion.base
        val delay = ghost.delayTicks
        val schedule = schedule(motion, last, despawn) { track.isSnap(it - delay) }
        val shift = motion.anchorRot.rotate(ghost.offset * motion.scale)
        return SkinRigBone.entries.map { bone ->
            element(
                id = "${ghost.id}.${bone.yamlName}",
                kind = VfxElementKind.SkinBone(bone, track.body.slim, ghost = true),
                spawn = spawn, despawn = despawn, last = last, schedule = schedule,
            ) { l ->
                val t = track.at(maxOf(0, l - delay)).transforms.bones.getValue(bone)
                Pose(
                    motion.anchorRot.rotate(Vec3(t.translation.x, t.translation.y, t.translation.z) * motion.scale) + shift,
                    motion.anchorRot * Quaternion(t.rotation.x, t.rotation.y, t.rotation.z, t.rotation.w),
                    t.scale * motion.scale,
                )
            }
        }
    }

    /** Ticks on which at least one performance element jumps (interpolation 0): the runtime sends them as one bundle. */
    public fun cutTicks(elements: List<VfxElementPlan>): Set<Int> = elements
        .filter { (it.kind as? VfxElementKind.SkinBone)?.ghost != true && (it.kind is VfxElementKind.SkinBone || it.kind is VfxElementKind.MainHandWeapon) }
        .flatMap { e -> e.updates.filter { it.interpolationTicks == 0 && !it.keepTransform }.map { it.tick } }
        .toSet()
}

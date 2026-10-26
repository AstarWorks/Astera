package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.skill.BlockDamageSpec
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.core.application.service.CoverBlockService
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Skill-side door to cover damage (ADR-0280). A skill reaches cover only through its `block_damage` /
 * `block_place` fields; everything goes through the one [CoverBlockService] that field tool swings use.
 *
 * - Projectile skills strike where the projectile lands ([onImpact]).
 * - Every other archetype strikes at cast time ([onCast]): AOE around the cast point, the rest along the look ray
 *   (or a line of `reach` blocks for `shape: line`).
 * - `block_place` raises a temporary wall at cast time.
 */
public class SkillCoverEffects(
    private val cover: () -> CoverBlockService?,
) {
    /** Cast-time effects of [spec]; projectile skills only place (their damage rides the projectile). */
    public fun onCast(spec: SkillSpec, caster: PlayerId, at: Vec3, pose: PlayerPose?) {
        val service = cover() ?: return
        val world = pose?.world ?: return
        spec.blockPlace?.let { place ->
            service.placeWall(caster, world, pose.position, pose.direction, place.distance, place.width, place.height, place.block)
        }
        val damage = spec.blockDamage ?: return
        if (spec.archetype == SkillArchetype.PROJECTILE) return
        val profile = damage.profile()
        val feedback = feedbackOf(damage)
        if (spec.archetype == SkillArchetype.AOE) {
            service.strikeBall(caster, world, at, damage.radius, spec.id, profile, feedback)
            return
        }
        val reach = if (damage.reach > 0.0) damage.reach else DEFAULT_REACH
        when (damage.shape) {
            BlockDamageSpec.Shape.LINE ->
                service.strikeLine(caster, world, pose.eyePosition, pose.direction, reach, damage.radius, spec.id, profile, feedback)
            BlockDamageSpec.Shape.BALL ->
                service.strikeRay(caster, world, pose.eyePosition, pose.direction, reach, damage.radius, spec.id, profile, feedback)
        }
    }

    /** A projectile of [skillId] landed at [at]. */
    public fun onImpact(skillId: SkillId, owner: PlayerId, world: WorldId?, at: Vec3, damage: BlockDamageSpec?) {
        if (damage == null || world == null) return
        val service = cover() ?: return
        service.strikeBall(owner, world, at, damage.radius, skillId, damage.profile(), feedbackOf(damage))
    }

    // A skill that counts as a tool (tool skills) sounds like a swing of it; a flat chip only voices the break.
    private fun feedbackOf(damage: BlockDamageSpec): CoverBlockService.Feedback =
        damage.tool?.let { CoverBlockService.Feedback.swing(it.kind) } ?: CoverBlockService.Feedback.CHIP

    public companion object {
        /** Look-ray length for a cast-time skill that sets `block_damage` without `reach`. */
        public const val DEFAULT_REACH: Double = 4.0
    }
}

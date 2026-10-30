package com.astarworks.astera.combat.domain.projectile

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Ties an in-flight projectile back to the skill cast that launched it so the
 * impact resolver can find the skill (ADR-0174).
 *
 * @property castOrigin caster feet position at cast time (the `Caster` /
 *   `Origin` targeter anchor for every later phase).
 * @property worldId world the cast happened in; `null` when unknown.
 * @property pose caster pose at cast time (look direction for later phases); `null` when unknown.
 */
public data class ProjectileSkillBinding(
    val skillId: SkillId,
    val castOrigin: Vec3,
    val worldId: WorldId?,
    val pose: PlayerPose? = null,
)

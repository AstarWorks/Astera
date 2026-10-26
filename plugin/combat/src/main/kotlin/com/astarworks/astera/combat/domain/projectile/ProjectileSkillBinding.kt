package com.astarworks.astera.combat.domain.projectile

import com.astarworks.astera.combat.domain.skill.BlockDamageSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Ties an in-flight projectile back to the skill cast that launched it so the
 * impact resolver can run the skill's lifecycle phases (ADR-0077 / ADR-0174).
 *
 * @property phases the phase lists already composed with item `phase_overrides`.
 * @property castOrigin caster feet position at cast time (the `Caster` /
 *   `Origin` targeter anchor for every later phase).
 * @property worldId world the cast happened in; `null` when unknown.
 * @property blockDamage the skill's opt-in cover damage applied where the projectile lands (ADR-0280); `null` = none.
 * @property pose caster pose at cast time (look direction for later phases); `null` when unknown.
 */
public data class ProjectileSkillBinding(
    val skillId: SkillId,
    val phases: PhaseMechanics.Projectile,
    val castOrigin: Vec3,
    val worldId: WorldId?,
    val pose: PlayerPose? = null,
    val blockDamage: BlockDamageSpec? = null,
)

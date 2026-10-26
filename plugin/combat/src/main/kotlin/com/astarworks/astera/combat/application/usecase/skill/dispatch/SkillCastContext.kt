package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.domain.event.SkillCast

/**
 * Cast-phase [MechanicContext] for [this] event: caster id, feet origin, the
 * world and look direction from [SkillCast.pose] (ADR-0174), and the skill on
 * the cast stack. Without this, `SpawnParticle` / `PlaySound` drop (null
 * world) and `FromCaster` direction has no facing.
 */
internal fun SkillCast.toMechanicContext(): MechanicContext = MechanicContext(
    actorId = caster,
    maxTargets = maxTargets,
    origin = at,
    worldId = pose?.world,
    castedSkills = setOf(skillId),
    pose = pose,
)

package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

public data class SkillCast(
    val caster: PlayerId,
    val skillId: SkillId,
    val at: Vec3,
    val atTick: Long,
) : DomainEvent

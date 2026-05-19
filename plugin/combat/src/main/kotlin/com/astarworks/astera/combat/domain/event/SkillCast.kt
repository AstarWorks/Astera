package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

public data class SkillCast(
    val caster: PlayerId,
    val skillId: SkillId,
    val at: Vec3,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

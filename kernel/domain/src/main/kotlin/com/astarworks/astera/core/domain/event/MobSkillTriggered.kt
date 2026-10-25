package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.mob.MobSkillBinding
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.skill.SkillId
import java.time.Instant

/**
 * Published by `MobSkillRouter` whenever a [MobSkillBinding] fires on a
 * spawned mob (Wave 5pve, ADR-0085).
 *
 * The router publishes this event **in addition to** the
 * [SkillCastRequested] event that drives combat resolution — Subscribers that
 * care about mob-attribution (analytics, "boss skill happened" announcers,
 * audit) listen to [MobSkillTriggered]; subscribers that care about skill
 * resolution (Combat) listen to [SkillCastRequested] regardless of whether
 * the caster was a player or a mob.
 *
 * @property handle Mob whose binding fired.
 * @property mobTypeId Registry id of the firing mob.
 * @property skillId Skill that was cast.
 * @property trigger The actual [MobSkillBinding] subclass instance — keeps
 *   the cause discoverable (`OnDamaged` / `OnPlayerNear` / `OnHpThreshold` / `OnDeath` / `Periodic`).
 * @property atTick Server tick at trigger time.
 */
public data class MobSkillTriggered(
    public val handle: EntityHandle,
    public val mobTypeId: MobTypeId,
    public val skillId: SkillId,
    public val trigger: MobSkillBinding,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent

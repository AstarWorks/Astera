package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * The player let go of a held skill (early release of a charge). The skill engine raises `release` on every running cast
 * of [caster] that has not released yet (`until: release` states end, `on: release` handlers run).
 */
public data class SkillReleased(
    val caster: PlayerId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

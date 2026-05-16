package com.astarworks.astera.combat.domain.damage

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.domain.weapon.DamageProfile

/**
 * Inbound damage event prior to resolution.
 *
 * Carries the raw intent: target, baseline profile, source, and flags
 * (e.g. [headshot]). [com.astarworks.astera.combat.domain.rule.DamageRule] converts
 * a [DamageAttempt] into a [ResolvedDamage] by applying multipliers.
 */
public data class DamageAttempt(
    val targetId: PlayerId,
    val baseProfile: DamageProfile,
    val source: DamageSource,
    val headshot: Boolean = false,
)

package com.astarworks.astera.domain.model.damage

import com.astarworks.astera.domain.model.player.PlayerId
import com.astarworks.astera.domain.model.weapon.DamageProfile

/**
 * Inbound damage event prior to resolution.
 *
 * Carries the raw intent: target, baseline profile, source, and flags
 * (e.g. [headshot]). [com.astarworks.astera.domain.rule.DamageRule] converts
 * a [DamageAttempt] into a [ResolvedDamage] by applying multipliers.
 */
public data class DamageAttempt(
    val targetId: PlayerId,
    val baseProfile: DamageProfile,
    val source: DamageSource,
    val headshot: Boolean = false,
)

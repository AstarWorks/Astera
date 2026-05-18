package com.astarworks.astera.combat.domain.armor

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Static definition of a piece of armor.
 *
 * [reduction] is an [ArmorReduction] so a single piece can resist different
 * [DamageAttribute]s by different amounts. Wave 5a-fix upgraded this from a
 * flat `damageReduction: Double` — see ADR-0031 for the long-term plan
 * (resists move into the universal attribute container in Wave 5e).
 */
public data class ArmorSpec(
    val id: ArmorId,
    val displayNameKey: MessageKey,
    val slot: ArmorSlot,
    val reduction: ArmorReduction,
    val setKey: ArmorSetKey? = null,
)

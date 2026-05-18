package com.astarworks.astera.combat.domain.armor

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Static definition of a piece of armor (worker α fills shape).
 *
 * Wave 5a leaves this as a Phase-A shell; worker α (Armor) completes:
 *  - `damageReduction` semantics (0.0 = none, 1.0 = full)
 *  - `setKey` for set-bonus aggregation
 *  - YAML schema in `ArmorYamlConfig` + loader service
 */
public data class ArmorSpec(
    val id: ArmorId,
    val displayNameKey: MessageKey,
    val slot: ArmorSlot,
    val damageReduction: Double,
    val setKey: ArmorSetKey? = null,
) {
    init {
        require(damageReduction in 0.0..1.0) {
            "ArmorSpec.damageReduction must be in [0.0, 1.0]: $damageReduction"
        }
    }
}

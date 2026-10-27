package com.astarworks.astera.core.domain.item

import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.SkillTrigger

/**
 * One trigger → skill mapping on an [ItemSpec] (Wave _items-1, ADR-0073;
 * extended Wave _skills-2, ADR-0077).
 *
 * Bindings make the item the SSoT for "what does pressing X do while holding
 * this thing": pre-ADR-0073 the mapping lived in `:plugin:combat`'s skill
 * registry indexed by weapon, which forced combat to know about every item
 * shape (armor, consumable, …). After ADR-0073 combat resolves the skill by
 * id; the binding lives next to the item.
 *
 * # Cooldown override
 *
 * Each skill carries a default cooldown in its own definition.
 * [cooldownOverrideTicks] lets an item shorten / lengthen that cooldown
 * *only when fired through this binding*. `null` means "use the skill's
 * default". Negative values are rejected at construction.
 */
public data class ItemSkillBinding(
    val trigger: SkillTrigger,
    val skillId: SkillId,
    val cooldownOverrideTicks: Int? = null,
) {
    init {
        cooldownOverrideTicks?.let {
            require(it >= 0) {
                "ItemSkillBinding.cooldownOverrideTicks must be >= 0, got $it"
            }
        }
    }
}

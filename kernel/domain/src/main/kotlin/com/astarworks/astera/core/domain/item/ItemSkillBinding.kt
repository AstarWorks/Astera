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
 *
 * # Phase overrides (Wave _skills-2, ADR-0077)
 *
 * [phaseOverrides] is a binding-side decoration for the skill's per-phase
 * mechanic list. The dispatcher composes `prepend + base + append` (or uses
 * `replace` outright) when an item-bound trigger fires. Wave _skills-1
 * named this field `hookOverrides` against a now-removed 4-hook model; the
 * Phase-based replacement is archetype-aware (see [PhaseOverrides.applyTo]).
 * Defaults to [PhaseOverrides.EMPTY] so existing items needn't specify it.
 *
 * # Norma level gate
 *
 * [minNormaLevel] is the in-match Norma level (ADR-0268) the holder must have
 * reached before this binding fires; below it the cast is refused with an
 * "unlocks at Lv N" hint. `1` (the default) means ungated. Only meaningful in a
 * match that runs the Norma layer; outside one the gate is not consulted.
 */
public data class ItemSkillBinding(
    val trigger: SkillTrigger,
    val skillId: SkillId,
    val cooldownOverrideTicks: Int? = null,
    val phaseOverrides: PhaseOverrides = PhaseOverrides.EMPTY,
    val minNormaLevel: Int = 1,
) {
    init {
        require(minNormaLevel >= 1) { "ItemSkillBinding.minNormaLevel must be >= 1, got $minNormaLevel" }
        cooldownOverrideTicks?.let {
            require(it >= 0) {
                "ItemSkillBinding.cooldownOverrideTicks must be >= 0, got $it"
            }
        }
    }
}

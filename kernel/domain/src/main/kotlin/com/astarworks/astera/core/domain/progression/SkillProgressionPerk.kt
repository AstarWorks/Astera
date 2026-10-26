package com.astarworks.astera.core.domain.progression

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Perk granted at a specific level on a specific skill axis
 * (Wave 5multi-skill, ADR-0101).
 *
 * Perks are pure declarative records: a level threshold, an identifier, an
 * optional display key, and a typed [SkillPerkEffect]. The grant mechanism
 * (apply to PlayerAttributeContainer, send notification, …) lives in the
 * application layer.
 *
 * IDs are kebab-case (`combat-damage-1`) for the same reasons as [SkillId]:
 * collision-free, YAML-friendly, no Unicode normalisation traps.
 */
public data class SkillProgressionPerk(
    public val id: String,
    public val level: Int,
    public val effect: SkillPerkEffect,
    public val displayKey: MessageKey? = null,
) {
    init {
        require(level >= 1) { "Perk '$id' level must be >= 1, got $level" }
        require(VALID_ID.matches(id)) {
            "Perk id '$id' must be kebab-case (a-z, 0-9, -)"
        }
    }

    private companion object {
        val VALID_ID = Regex("^[a-z][a-z0-9-]*$")
    }
}

/**
 * Declarative perk effect. Phase 1 ships three variants that map directly to
 * the [com.astarworks.astera.core.domain.attribute.AttributeKey] surface:
 *
 *  - [StatMultiplier] — multiplicative modifier on a numeric attribute
 *  - [StatAdditive]   — additive modifier on a numeric attribute
 *  - [UnlockFeature]  — abstract feature unlock (Wave 7+ HSB Collections, etc.)
 *
 * The actual application path is wave-deferred (a Phase 2 listener applies
 * StatMultiplier / StatAdditive to the player attribute container); this
 * sealed shape just guarantees the YAML round-trip is type-safe today.
 */
public sealed interface SkillPerkEffect {

    public data class StatMultiplier(val stat: String, val multiplier: Double) : SkillPerkEffect {
        init {
            require(stat.isNotBlank()) { "StatMultiplier.stat must not be blank" }
            require(multiplier > 0.0) { "StatMultiplier.multiplier must be > 0, got $multiplier" }
        }
    }

    public data class StatAdditive(val stat: String, val additive: Double) : SkillPerkEffect {
        init {
            require(stat.isNotBlank()) { "StatAdditive.stat must not be blank" }
        }
    }

    public data class UnlockFeature(val featureId: String) : SkillPerkEffect {
        init {
            require(featureId.isNotBlank()) { "UnlockFeature.featureId must not be blank" }
        }
    }
}

package com.astarworks.astera.combat.domain.combo

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Authored combo rule: weapon × pattern → finisher (Wave 5combat-depth, ADR-0105).
 *
 * A [ComboRoute] is YAML content under `content/combat-depth/combos/`
 * that says "while wielding [weaponId], if your combo state matches
 * [pattern], cast [finisher] and apply [bonusDamageMultiplier] to the
 * next strike". The runtime matches routes on every recorded step;
 * the first matching route fires.
 *
 * # Why match-and-fire is one-shot
 *
 * If the route remained eligible after firing, a single chain would
 * spam the finisher every subsequent tick. Per ADR-0105, the tracker
 * resets the [ComboState] after a route fires so the player must
 * rebuild the chain. This keeps the combo as a discrete reward rather
 * than a sustained buff.
 *
 * @property id Registry id (`astera:sword-finisher`).
 * @property weaponId Weapon this route requires. The combo state is
 *   indexed by weapon, so a route never matches a foreign weapon's
 *   chain.
 * @property pattern Match condition; see [ComboPattern].
 * @property finisher Skill cast on match. Resolved through
 *   `ISkillRegistry`. The cast itself goes through the existing
 *   `CastSkillUseCase` pipeline (cooldown / mana checks still apply).
 * @property bonusDamageMultiplier Multiplier applied to the **next**
 *   damage step that matches this route's weapon. 1.0 = no bonus,
 *   1.5 = +50%. Caps at 5.0 to keep PvP integrity (`bonusDamageMultiplier`
 *   higher than 5x would let combos one-shot any vanilla armor set).
 */
public data class ComboRoute(
    public val id: ComboRouteId,
    public val weaponId: ItemId,
    public val pattern: ComboPattern,
    public val finisher: SkillId,
    public val bonusDamageMultiplier: Double = 1.0,
) {
    init {
        require(bonusDamageMultiplier.isFinite()) {
            "ComboRoute($id).bonusDamageMultiplier must be finite: $bonusDamageMultiplier"
        }
        require(bonusDamageMultiplier in MIN_MULTIPLIER..MAX_MULTIPLIER) {
            "ComboRoute($id).bonusDamageMultiplier must be in [$MIN_MULTIPLIER, $MAX_MULTIPLIER]: $bonusDamageMultiplier"
        }
    }

    public companion object {
        public const val MIN_MULTIPLIER: Double = 1.0
        public const val MAX_MULTIPLIER: Double = 5.0
    }
}

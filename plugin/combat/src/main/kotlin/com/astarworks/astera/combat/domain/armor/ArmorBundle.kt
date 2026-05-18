package com.astarworks.astera.combat.domain.armor

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Snapshot of a player's currently-equipped armor across all four slots.
 *
 * `ArmorReductionModifier` walks [equipped] to compute the per-attribute
 * total reduction at hit-resolution time; [totalReduction] is exposed here
 * for tests and analytics that want the same number without running the
 * pipeline.
 *
 * Cap (80%) is applied by `ArmorReductionModifier`, not by this snapshot —
 * the raw sum is reported here so debug overlays can show "armor reductions
 * sum to 92%, capped to 80%".
 */
public data class ArmorBundle(
    val playerId: PlayerId,
    val equipped: Map<ArmorSlot, ArmorSpec>,
) {
    /**
     * Sum of per-slot reductions against [attr]. Set bonuses are NOT included
     * (they require an `IArmorRegistry` lookup); use `ArmorReductionModifier`
     * for the cap-aware, set-bonus-aware final reduction.
     */
    public fun totalReduction(attr: DamageAttribute): Double =
        equipped.values.sumOf { it.reduction.forAttribute(attr) }

    public companion object {
        public fun empty(playerId: PlayerId): ArmorBundle =
            ArmorBundle(playerId, equipped = emptyMap())
    }
}

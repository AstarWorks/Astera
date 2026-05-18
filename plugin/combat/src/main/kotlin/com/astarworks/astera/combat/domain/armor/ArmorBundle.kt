package com.astarworks.astera.combat.domain.armor

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Snapshot of a player's currently-equipped armor across all four slots.
 * Worker α fills the body (per-slot stacking, set-bonus aggregation).
 *
 * Phase A note: kept minimal so `ArmorReductionModifier` (also α-owned) can
 * be the single place that walks the slots — keeps the slot iteration order
 * deterministic for test assertions.
 */
public data class ArmorBundle(
    val playerId: PlayerId,
    val equipped: Map<ArmorSlot, ArmorSpec>,
) {
    public companion object {
        public fun empty(playerId: PlayerId): ArmorBundle =
            ArmorBundle(playerId, equipped = emptyMap())
    }
}

package com.astarworks.astera.economy.domain.power

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Aggregated "Networth / Power Index" for a player (Wave 5flex / ADR-0102
 * surface #4).
 *
 * Direct port of the HSB Networth pattern (per
 * `docs/competitive-analysis/01-hypixel-skyblock.md`): every piece of
 * value the player owns boils down to a single monotonic Long, surfaced
 * across nameplate badges, profile cards, and leaderboards (Wave 7b
 * integration).
 *
 * # Components
 *
 *   - [equipmentValue] — sum of estimated marketplace value for every
 *     [com.astarworks.astera.items.domain.refinement.ItemInstance] the
 *     player owns (refinement tier moves the needle here).
 *   - [vaultValue] — sum of currency-equivalent value of items stored
 *     in the Wave 5e PersonalVault.
 *   - [walletValue] — sum of every currency balance, normalised to a
 *     reference currency via [com.astarworks.astera.core.domain.economy.Currency]
 *     weights (the calculator hands in the conversion).
 *   - [marketplaceListingValue] — listings currently posted by this
 *     player; the asking price counts as "potential income".
 *
 * # Why a snapshot
 *
 * The aggregate is *computed* state (no source of truth lives here),
 * but Wave _ops-4 wants Prometheus gauges and Wave 7b wants leaderboard
 * sort keys. Computing on every read is expensive when the equipment
 * set is large; computing on a schedule + persisting snapshots keeps
 * the hot path constant-time. The [computedAt] timestamp lets the
 * consumer know how fresh the snapshot is.
 *
 * # Total
 *
 * [total] is the sum of every component, capped at `Long.MAX_VALUE` to
 * defend against degenerate "billion legendary swords" attacks. The
 * cap is loud (logged at the calculator level) so ops sees abuse
 * patterns immediately.
 */
public data class PowerIndex(
    public val playerId: PlayerId,
    public val equipmentValue: Long,
    public val vaultValue: Long,
    public val walletValue: Long,
    public val marketplaceListingValue: Long,
    public val computedAt: Instant,
) {
    init {
        require(equipmentValue >= 0) {
            "PowerIndex.equipmentValue must be >= 0, got $equipmentValue"
        }
        require(vaultValue >= 0) {
            "PowerIndex.vaultValue must be >= 0, got $vaultValue"
        }
        require(walletValue >= 0) {
            "PowerIndex.walletValue must be >= 0, got $walletValue"
        }
        require(marketplaceListingValue >= 0) {
            "PowerIndex.marketplaceListingValue must be >= 0, got $marketplaceListingValue"
        }
    }

    /**
     * Sum of every component, saturating at [Long.MAX_VALUE] (loud
     * overflow is preferable to silent wrap-around for a value
     * surfaced on nameplates + leaderboards).
     */
    public val total: Long
        get() = saturatingAdd(
            saturatingAdd(equipmentValue, vaultValue),
            saturatingAdd(walletValue, marketplaceListingValue),
        )

    public companion object {
        /** Empty snapshot for players with no equipment / vault / wallet. */
        public fun empty(playerId: PlayerId, at: Instant): PowerIndex = PowerIndex(
            playerId = playerId,
            equipmentValue = 0L,
            vaultValue = 0L,
            walletValue = 0L,
            marketplaceListingValue = 0L,
            computedAt = at,
        )

        /** Saturating add: returns [Long.MAX_VALUE] on overflow. */
        internal fun saturatingAdd(a: Long, b: Long): Long {
            if (a == 0L) return b
            if (b == 0L) return a
            val sum = a + b
            // Overflow detected when signs of a, b agree but sum disagrees.
            return if ((a xor sum) and (b xor sum) < 0L) Long.MAX_VALUE else sum
        }
    }
}

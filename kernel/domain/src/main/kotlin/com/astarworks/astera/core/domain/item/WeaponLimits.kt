package com.astarworks.astera.core.domain.item

/**
 * How many weapons a player may carry into / hold during a match (RTM-style MAIN + SUB loadout cap),
 * declared per game mode in `content/game-modes/<id>.yaml`:
 *
 * ```yaml
 * default_config:
 *   weapon_limits: "gun:1,sword:1,staff:1,total:2"   # at most 1 per family, at most 2 weapons overall
 * ```
 *
 * Tokens are `<gun|sword|staff>:<n>` (per-family cap) and `total:<n>` (overall cap); an absent token
 * means unlimited. An empty / missing value is [UNLIMITED].
 */
public data class WeaponLimits(
    public val perCategory: Map<WeaponCategory, Int> = emptyMap(),
    public val total: Int? = null,
) {
    /** `true` when no cap is configured. */
    public val isUnlimited: Boolean get() = perCategory.isEmpty() && total == null

    /** Why [candidate] cannot be added to [held] (in slot order), or `null` when it fits. */
    public fun blockerFor(held: List<WeaponCategory>, candidate: WeaponCategory): Blocker? {
        total?.let { if (held.size >= it) return Blocker.Total(it) }
        perCategory[candidate]?.let { cap ->
            if (held.count { it == candidate } >= cap) return Blocker.Category(candidate, cap)
        }
        return null
    }

    /**
     * Indices of [held] (slot order) that exceed the caps; the earliest weapons are kept. Used on
     * match entry to trim an over-full inventory.
     */
    public fun excessIndices(held: List<WeaponCategory>): List<Int> {
        val kept = mutableListOf<WeaponCategory>()
        val excess = mutableListOf<Int>()
        held.forEachIndexed { index, category ->
            if (blockerFor(kept, category) == null) kept += category else excess += index
        }
        return excess
    }

    /** What stopped a weapon: the overall cap or one family's cap. */
    public sealed class Blocker {
        public data class Total(public val cap: Int) : Blocker()
        public data class Category(public val category: WeaponCategory, public val cap: Int) : Blocker()
    }

    public companion object {
        public const val CONFIG_KEY: String = "weapon_limits"
        public val UNLIMITED: WeaponLimits = WeaponLimits()

        /** Lenient parse: unknown tokens and non-integer / negative values are ignored. */
        public fun parse(raw: String?): WeaponLimits {
            if (raw.isNullOrBlank()) return UNLIMITED
            val perCategory = mutableMapOf<WeaponCategory, Int>()
            var total: Int? = null
            for (token in raw.split(',')) {
                val parts = token.split(':', limit = 2).map { it.trim() }
                if (parts.size != 2) continue
                val n = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: continue
                if (parts[0].equals("total", ignoreCase = true)) {
                    total = n
                } else {
                    WeaponCategory.parse(parts[0])?.let { perCategory[it] = n }
                }
            }
            return WeaponLimits(perCategory, total)
        }
    }
}

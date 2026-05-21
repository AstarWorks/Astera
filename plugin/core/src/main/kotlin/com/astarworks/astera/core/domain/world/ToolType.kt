package com.astarworks.astera.core.domain.world

/**
 * Two-axis tool descriptor: [kind] × [tier] (Wave 5x, ADR-0090).
 *
 * # Why two axes?
 *
 * Wave 5d shipped a single-axis enum (`NONE / WOOD / STONE / IRON / DIAMOND`)
 * that collapsed "what shape of tool" with "what material it's made of". That
 * was enough for the basic damage matrix but blocked the
 * pickaxe-vs-axe-vs-shovel rock-paper-scissors against block materials. Wave
 * 5x splits the axes: [ToolKind] (PICKAXE / AXE / SHOVEL / NONE) × [ToolTier]
 * (WOOD / STONE / IRON / DIAMOND / NETHERITE / NONE).
 *
 * # Matrix interpretation
 *
 * [BlockDurabilityRule.damageFor] consults a `Map<(kind, tier, material), Int>`
 * cell, defaulting to [BlockDurabilityRule.DEFAULT_DAMAGE] on a miss. Authors
 * author the matrix in `content/balance/block-durability.yaml` using the
 * `"PICKAXE/WOOD"` key convention (see [serialKey] / [fromSerialKey]).
 *
 * # Konsist enforcement
 *
 * `ArchitectureTest` includes a Wave-5x-specific rule that this type **must
 * remain a two-axis data class** (kind + tier). A single-axis collapse is
 * an architectural regression — the same regression Wave 5x un-did.
 *
 * # Companion helpers
 *
 * [NONE] / [WOODEN_PICKAXE] / [STONE_AXE] / … are convenience constructors
 * for the common tools. Tests and the matrix loader use them; production
 * code that needs a tool computed from raw axes should call the constructor
 * directly.
 */
public data class ToolType(
    public val kind: ToolKind,
    public val tier: ToolTier,
) {
    init {
        // The only legal "both NONE" pairing is the empty-hand sentinel.
        // Single-NONE pairings would be ambiguous (a "wood-tier NONE" or a
        // "NONE-kind diamond" makes no physical sense).
        require(bothNoneOrBothSet()) {
            "ToolType: kind and tier must both be NONE or both non-NONE, got kind=$kind tier=$tier"
        }
    }

    private fun bothNoneOrBothSet(): Boolean {
        val kindIsNone = kind == ToolKind.NONE
        val tierIsNone = tier == ToolTier.NONE
        return kindIsNone == tierIsNone
    }

    /**
     * Canonical "PICKAXE/WOOD" / "AXE/IRON" string for the YAML matrix.
     * The empty-hand sentinel renders as "NONE".
     */
    public fun serialKey(): String = if (kind == ToolKind.NONE) "NONE" else "${kind.name}/${tier.name}"

    override fun toString(): String = serialKey()

    public companion object {
        /** Empty-hand sentinel (kind == NONE, tier == NONE). */
        public val NONE: ToolType = ToolType(ToolKind.NONE, ToolTier.NONE)

        public val WOODEN_PICKAXE: ToolType = ToolType(ToolKind.PICKAXE, ToolTier.WOOD)
        public val STONE_PICKAXE: ToolType = ToolType(ToolKind.PICKAXE, ToolTier.STONE)
        public val IRON_PICKAXE: ToolType = ToolType(ToolKind.PICKAXE, ToolTier.IRON)
        public val DIAMOND_PICKAXE: ToolType = ToolType(ToolKind.PICKAXE, ToolTier.DIAMOND)
        public val NETHERITE_PICKAXE: ToolType = ToolType(ToolKind.PICKAXE, ToolTier.NETHERITE)

        public val WOODEN_AXE: ToolType = ToolType(ToolKind.AXE, ToolTier.WOOD)
        public val STONE_AXE: ToolType = ToolType(ToolKind.AXE, ToolTier.STONE)
        public val IRON_AXE: ToolType = ToolType(ToolKind.AXE, ToolTier.IRON)
        public val DIAMOND_AXE: ToolType = ToolType(ToolKind.AXE, ToolTier.DIAMOND)
        public val NETHERITE_AXE: ToolType = ToolType(ToolKind.AXE, ToolTier.NETHERITE)

        public val WOODEN_SHOVEL: ToolType = ToolType(ToolKind.SHOVEL, ToolTier.WOOD)
        public val STONE_SHOVEL: ToolType = ToolType(ToolKind.SHOVEL, ToolTier.STONE)
        public val IRON_SHOVEL: ToolType = ToolType(ToolKind.SHOVEL, ToolTier.IRON)
        public val DIAMOND_SHOVEL: ToolType = ToolType(ToolKind.SHOVEL, ToolTier.DIAMOND)
        public val NETHERITE_SHOVEL: ToolType = ToolType(ToolKind.SHOVEL, ToolTier.NETHERITE)

        /**
         * Parse the canonical "PICKAXE/WOOD" key (or `"NONE"`) used by YAML
         * configs. Case-insensitive; returns `null` on malformed input so
         * callers can surface a typed config error instead of an exception.
         */
        public fun fromSerialKey(raw: String): ToolType? {
            val normalized = raw.trim().uppercase()
            if (normalized == "NONE" || normalized == "NONE/NONE") return NONE
            val parts = normalized.split('/')
            if (parts.size != 2) return null
            val kind = runCatching { ToolKind.valueOf(parts[0]) }.getOrNull() ?: return null
            val tier = runCatching { ToolTier.valueOf(parts[1]) }.getOrNull() ?: return null
            return runCatching { ToolType(kind, tier) }.getOrNull()
        }
    }
}

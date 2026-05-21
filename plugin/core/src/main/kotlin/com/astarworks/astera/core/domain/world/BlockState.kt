package com.astarworks.astera.core.domain.world

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-position block durability state (Wave 5d → Wave 5x, ADR-0090).
 *
 * Immutable value record tracked by `IBlockStateGateway`. Each tracked
 * position carries:
 *  - the current durability (0..[maxHp]),
 *  - the per-block-type maximum [maxHp] (custom blocks may set this far
 *    above 100 — e.g. 5000 HP "hardened soil" mimicking obsidian),
 *  - the tick when it was last damaged (for pseudo-recovery scheduling),
 *  - the player credited with the most recent hit (for kill / break
 *    attribution).
 *
 * # Wave 5x: per-block-type maxHp (ADR-0090)
 *
 * Pre-5x all blocks shared `MAX_DURABILITY = 100`. Wave 5x makes the maximum
 * a per-instance field so `CustomBlockSpec.baseDurability` can dictate how
 * "tanky" a custom block is. Vanilla / unknown blocks default to
 * [DEFAULT_MAX_DURABILITY] (100), preserving the Wave 5d semantics for
 * untouched call sites.
 *
 * **Why a dedicated data class, not `IAttributeContainer`?**
 *
 * ADR-0033 §"BlockState shape" / ADR-0058 §5: HP is a singular state, not a
 * base-plus-modifier overlay. `lastDamagedTick` / `damagedBy` are metadata,
 * not numeric modifiers, so they don't compose via ADD / MULTIPLY semantics.
 * Forcing them into `AttributeValue` would invert the container's mental
 * model. A future ADR may revisit if cross-system block modifiers (e.g.
 * barriers granting 1.5× HP) become a requirement.
 */
public data class BlockState(
    val blockType: BlockType,
    val durability: Int,
    val lastDamagedTick: Long,
    val damagedBy: PlayerId?,
    val maxHp: Int = DEFAULT_MAX_DURABILITY,
) {
    init {
        require(maxHp >= 1) {
            "BlockState.maxHp must be >= 1, was $maxHp"
        }
        require(durability in 0..maxHp) {
            "BlockState.durability must be in 0..$maxHp, was $durability"
        }
        require(lastDamagedTick >= 0L) {
            "BlockState.lastDamagedTick must be >= 0, was $lastDamagedTick"
        }
    }

    /** True when durability has reached 0 (block is broken / consumed). */
    public val isBroken: Boolean get() = durability == 0

    public companion object {
        /**
         * Default cap for vanilla / unknown blocks (Wave 5d compatibility).
         * Custom blocks override via the per-instance [maxHp] field.
         */
        public const val DEFAULT_MAX_DURABILITY: Int = 100

        /**
         * Wave-5d alias kept for in-tree call sites that haven't migrated
         * inside Wave 5x. Identical to [DEFAULT_MAX_DURABILITY]; new code
         * should prefer the explicit name or the per-instance [maxHp] field.
         */
        public const val MAX_DURABILITY: Int = DEFAULT_MAX_DURABILITY
    }
}

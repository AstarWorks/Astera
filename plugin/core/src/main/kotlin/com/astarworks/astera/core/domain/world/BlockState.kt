package com.astarworks.astera.core.domain.world

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-position block durability state (Wave 5d).
 *
 * Immutable value record tracked by `IBlockStateGateway`. Each tracked
 * position carries the current durability (0..[MAX_DURABILITY]), the tick
 * when it was last damaged (for pseudo-recovery scheduling), and the player
 * credited with the most recent hit (for kill / break attribution).
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
) {
    init {
        require(durability in 0..MAX_DURABILITY) {
            "BlockState.durability must be in 0..$MAX_DURABILITY, was $durability"
        }
        require(lastDamagedTick >= 0L) {
            "BlockState.lastDamagedTick must be >= 0, was $lastDamagedTick"
        }
    }

    /** True when durability has reached 0 (block is broken / consumed). */
    public val isBroken: Boolean get() = durability == 0

    public companion object {
        public const val MAX_DURABILITY: Int = 100
    }
}

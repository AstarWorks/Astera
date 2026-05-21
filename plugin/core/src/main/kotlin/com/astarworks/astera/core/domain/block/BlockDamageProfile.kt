package com.astarworks.astera.core.domain.block

import com.astarworks.astera.core.domain.world.BlockMaterial
import com.astarworks.astera.core.domain.world.ToolType

/**
 * Per-cast damage profile carried from skill → block (Wave 5x, ADR-0090).
 *
 * Passed to `IBlockStateGateway.applyDamageFromSkill` so a skill engine can
 * say "this skill should act as if it were a `DIAMOND_PICKAXE` swinging at
 * `materialOverride` for `baseAmount` raw damage". The gateway resolves the
 * actual damage by consulting the per-block-type `toolEffectiveness` matrix
 * (from `CustomBlockSpec`) — or, falling back, the global
 * `BlockDurabilityRule` — at lookup time.
 *
 * # Fields
 *
 * - [baseAmount] — pre-modifier damage the skill wants to deal.
 *   `BlockDurabilityRule` multiplies the cell value by `(baseAmount /
 *   DEFAULT_BASE)` so a skill with `baseAmount = 50` against a cell whose
 *   matrix entry is 100 lands as 50 actual HP damage.
 * - [effectiveTool] — which `ToolType` the skill should "count as". A
 *   fist-based earth skill might cast as `DIAMOND_PICKAXE` so it can crack
 *   stone; a sword skill might cast as `IRON_AXE` so it shears wood.
 * - [materialOverride] — if non-null, the gateway uses this material for
 *   matrix lookup instead of the block's own classification. Lets a "shatter
 *   stone" skill bypass an exotic custom block's `material = OTHER` slot
 *   and treat it as STONE.
 */
public data class BlockDamageProfile(
    val baseAmount: Int,
    val effectiveTool: ToolType,
    val materialOverride: BlockMaterial? = null,
) {
    init {
        require(baseAmount >= 0) {
            "BlockDamageProfile.baseAmount must be >= 0, got $baseAmount"
        }
    }

    public companion object {
        /**
         * Reference base for the per-cell matrix. `BlockDurabilityRule` cells
         * are authored as "damage to a 100-HP block on one hit assuming a
         * baseAmount of [DEFAULT_BASE]". Skills with a different baseAmount
         * scale linearly: `actual = cell * baseAmount / DEFAULT_BASE`.
         */
        public const val DEFAULT_BASE: Int = 100
    }
}

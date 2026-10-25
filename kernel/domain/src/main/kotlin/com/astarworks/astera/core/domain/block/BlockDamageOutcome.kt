package com.astarworks.astera.core.domain.block

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.BlockChange
import com.astarworks.astera.core.domain.world.BlockState

/**
 * Result of a skill→block damage application (Wave 5x, ADR-0090).
 *
 * Returned by `IBlockStateGateway.applyDamageFromSkill` so the caller learns
 * the post-damage block state without a separate query. The three variants
 * cover every observable outcome:
 *
 *  - [Damaged]: HP reduced, block intact. The caller may surface the new
 *    durability in HUD / particle effects.
 *  - [Broken]: HP hit 0, block consumed. Drops are pre-resolved per
 *    `CustomBlockSpec.drops` (tool requirement + dropChance roll). The
 *    paired [BlockChange.Broken] is what the journal will record.
 *  - [Ineffective]: nothing happened — the tool is wrong for the material
 *    (e.g. shovel on stone), or the block is indestructible / immune.
 *
 * The gateway publishes `BlockDamagedBySkill` / `BlockBrokenBySkill`
 * domain events from inside `applyDamageFromSkill`; the outcome value is
 * the in-band return path for the use-case caller.
 */
public sealed interface BlockDamageOutcome {

    public data class Damaged(
        val newState: BlockState,
        val damageDealt: Int,
    ) : BlockDamageOutcome

    public data class Broken(
        val drops: List<ItemId>,
        val change: BlockChange.Broken,
    ) : BlockDamageOutcome

    public data class Ineffective(
        val reason: IneffectiveReason,
    ) : BlockDamageOutcome
}

/** Why a [BlockDamageOutcome.Ineffective] result was returned (Wave 5x). */
public sealed interface IneffectiveReason {
    /** The skill's effectiveTool doesn't match the block's required tool kind. */
    public data object WrongTool : IneffectiveReason

    /** The block is flagged as indestructible (e.g. Core block in match phase). */
    public data object IndestructibleBlock : IneffectiveReason

    /** The block's material is listed in the spec's `immuneTo` set. */
    public data object ImmuneByMaterial : IneffectiveReason

    /** Zero damage resolved through the matrix (e.g. NONE × STONE). */
    public data object ZeroDamageCell : IneffectiveReason
}

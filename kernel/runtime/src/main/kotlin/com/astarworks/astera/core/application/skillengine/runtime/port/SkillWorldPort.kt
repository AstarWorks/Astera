package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.application.skillengine.runtime.CastId
import com.astarworks.astera.core.domain.skillengine.world.BlockCell

public enum class BlockDamageOutcome { NONE, CHIPPED, BROKEN }

/**
 * Block seam of the skill engine (ADR-0301). Cells are in the caster's world. The gateway owns permission
 * (arena guard), custom cover durability and the match-end cover rebuild; the engine owns shapes, ordering
 * and reverting plain block edits (see SkillWorldEditor).
 */
public interface ISkillWorldGateway {
    /** Block key of [cell] (`minecraft:stone`), null for air. */
    public fun blockAt(cell: BlockCell): String?

    /** May the caster of [cast] edit [cell]? [outsideArena] lifts the arena restriction for this op. */
    public fun canEdit(cast: CastId, cell: BlockCell, outsideArena: Boolean): Boolean

    /** [cell] currently holds custom cover (own durability, rebuilt at match end). */
    public fun isCover(cell: BlockCell): Boolean

    /** [blockKey] is a custom cover block (placed with durability, never reverted by tick). */
    public fun isCoverBlock(blockKey: String): Boolean

    /** Put [blockKey] at [cell]. False when it was not placed (unknown key, no world): the gateway has logged why. */
    public fun place(cell: BlockCell, blockKey: String): Boolean

    public fun remove(cell: BlockCell)

    /** Place [blockKey] as temporary cover owned by the caster. False when it cannot be placed here. */
    public fun placeCover(cast: CastId, cell: BlockCell, blockKey: String): Boolean

    /**
     * Strike [cell] with [blockDamage] at the caster's tool class. The gateway removes the block itself when it
     * reaches 0 HP and reports BROKEN; plain blocks are restored by the engine, cover by the match-end rebuild.
     */
    public fun damage(cast: CastId, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome

    /** Take one [itemId] from the caster's inventory; false when none is left. */
    public fun consumeItem(cast: CastId, itemId: String): Boolean
}

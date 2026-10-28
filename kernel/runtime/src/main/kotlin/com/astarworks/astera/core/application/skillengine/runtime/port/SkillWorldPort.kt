package com.astarworks.astera.core.application.skillengine.runtime.port

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import com.astarworks.astera.core.domain.skillengine.world.BreakAreaSpec
import com.astarworks.astera.core.domain.skillengine.world.PlaceShapeSpec
import com.astarworks.astera.core.domain.skillengine.world.WorldAim
import java.util.UUID

public enum class BlockDamageOutcome { NONE, CHIPPED, BROKEN }

/**
 * World seam of the skill engine (ADR-0303 10.1c). One call per `Action.World` row: the gateway owns shape
 * ordering, the ripple schedule, permission (arena guard), cover durability and reverting every temporary edit.
 */
public interface ISkillWorldGateway {
    /** `break_area`: strike the shape's cells nearest-first, spread over `spec.rippleTicks` (0 = this tick). */
    public fun breakArea(cast: CastRef, spec: BreakAreaSpec, aim: WorldAim)

    /** `place_shape`: place the build's cells nearest-first, spread over `spec.rippleTicks` (0 = this tick). */
    public fun placeShape(cast: CastRef, spec: PlaceShapeSpec, aim: WorldAim)

    /** `explode`: exactly one vanilla explosion at [at]. */
    public fun explode(cast: CastRef, at: Vec3, power: Double, breakBlocks: Boolean, fire: Boolean)

    /** `lightning`: [visualOnly] strikes the visual effect only, otherwise a damaging strike. */
    public fun lightning(cast: CastRef, at: Vec3, visualOnly: Boolean)

    /** Advance the revert / ripple schedule by one server tick. */
    public fun tick()

    /** Restore every temporary edit now (cast cancel, match end, reload, shutdown). */
    public fun revertAll()
}

/**
 * Block-level seam under the shared ledger ([com.astarworks.astera.core.application.skillengine.runtime.world.LedgerWorldGateway]).
 * Cells are in the caster's world. The implementation owns permission (arena guard), custom cover durability and
 * the match-end cover rebuild; the ledger owns ordering and reverting plain block edits.
 */
public interface ISkillBlockAccess {
    /** Block key of [cell] (`minecraft:stone`), null for air. */
    public fun blockAt(cell: BlockCell): String?

    /** May [caster] edit [cell]? [outsideArena] lifts the arena restriction for this op. */
    public fun canEdit(caster: UUID, cell: BlockCell, outsideArena: Boolean): Boolean

    /** [cell] currently holds custom cover (own durability, rebuilt at match end). */
    public fun isCover(cell: BlockCell): Boolean

    /** [blockKey] is a custom cover block (placed with durability, never reverted by tick). */
    public fun isCoverBlock(blockKey: String): Boolean

    public fun place(cell: BlockCell, blockKey: String)

    public fun remove(cell: BlockCell)

    /** Place [blockKey] as temporary cover owned by the caster. False when it cannot be placed here. */
    public fun placeCover(caster: UUID, cell: BlockCell, blockKey: String): Boolean

    /**
     * Strike [cell] with [blockDamage] at [toolClass] (`axe|pickaxe|shovel`, null = bare hand). The implementation
     * removes the block itself when it reaches 0 HP and reports BROKEN; plain blocks are restored by the ledger,
     * cover by the match-end rebuild.
     */
    public fun damage(caster: UUID, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome

    /** Take one [itemId] from [caster]'s inventory; false when none is left. */
    public fun consumeItem(caster: UUID, itemId: String): Boolean
}

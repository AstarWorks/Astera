package com.astarworks.astera.testfixtures.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockDamageOutcome
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillBlockAccess
import com.astarworks.astera.core.application.skillengine.runtime.world.LedgerWorldGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.world.BlockCell
import java.util.UUID

/**
 * In-memory block world under the real shared ledger: `break_area` / `place_shape` run the production algorithm, only the
 * blocks are a map. Records every strike and placement with the tick it happened in, plus explosions and lightning.
 */
public class FakeSkillWorldGateway(private val permit: (BlockCell) -> Boolean = { true }) :
    LedgerWorldGateway(FakeBlocks()) {

    public data class Explosion(val caster: UUID, val at: Vec3, val power: Double, val breakBlocks: Boolean, val fire: Boolean)
    public data class Lightning(val caster: UUID, val at: Vec3, val visualOnly: Boolean)

    private val fake: FakeBlocks get() = blocks as FakeBlocks

    public val explosions: MutableList<Explosion> = ArrayList()
    public val lightnings: MutableList<Lightning> = ArrayList()

    /** Ticks since construction; advanced by [tick]. */
    public var now: Int
        get() = fake.now
        set(value) { fake.now = value }

    /** Cells hit by `damage`, in order, with the tick of the strike. */
    public val strikes: List<Pair<Int, BlockCell>> get() = fake.strikes

    /** Cells written by `place`, in order, with the tick (reverts included: a revert is a `place` or `remove` too). */
    public val placements: List<Pair<Int, BlockCell>> get() = fake.placements

    /** Items taken by `consumeItem`, in order. */
    public val consumed: List<String> get() = fake.consumed

    public fun setBlock(cell: BlockCell, key: String) { fake.world[cell] = key }

    public fun block(cell: BlockCell): String? = fake.world[cell]

    /** Items the caster holds; `consumeItem` fails when the count reaches 0. */
    public fun giveItem(itemId: String, count: Int) { fake.stock[itemId] = count }

    init {
        fake.permit = permit
    }

    override fun tick() {
        fake.now++
        super.tick()
    }

    override fun explode(cast: CastRef, at: Vec3, power: Double, breakBlocks: Boolean, fire: Boolean) {
        explosions += Explosion(cast.caster, at, power, breakBlocks, fire)
    }

    override fun lightning(cast: CastRef, at: Vec3, visualOnly: Boolean) {
        lightnings += Lightning(cast.caster, at, visualOnly)
    }
}

internal class FakeBlocks : ISkillBlockAccess {
    var now: Int = 0
    var permit: (BlockCell) -> Boolean = { true }
    val world: MutableMap<BlockCell, String> = HashMap()
    val stock: MutableMap<String, Int> = HashMap()
    val strikes: MutableList<Pair<Int, BlockCell>> = ArrayList()
    val placements: MutableList<Pair<Int, BlockCell>> = ArrayList()
    val consumed: MutableList<String> = ArrayList()

    override fun blockAt(cast: CastRef, cell: BlockCell): String? = world[cell]

    override fun canEdit(cast: CastRef, cell: BlockCell, outsideArena: Boolean): Boolean = outsideArena || permit(cell)

    override fun isCover(cast: CastRef, cell: BlockCell): Boolean = false

    override fun isCoverBlock(blockKey: String): Boolean = false

    override fun place(cast: CastRef, cell: BlockCell, blockKey: String) {
        world[cell] = blockKey
        placements += now to cell
    }

    override fun remove(cast: CastRef, cell: BlockCell) {
        world.remove(cell)
        placements += now to cell
    }

    override fun placeCover(cast: CastRef, cell: BlockCell, blockKey: String): Boolean = false

    override fun damage(cast: CastRef, cell: BlockCell, blockDamage: Int, toolClass: String?): BlockDamageOutcome {
        strikes += now to cell
        if (blockDamage <= 0 || world[cell] == null) return BlockDamageOutcome.NONE
        world.remove(cell)
        return BlockDamageOutcome.BROKEN
    }

    override fun consumeItem(cast: CastRef, itemId: String): Boolean {
        val have = stock[itemId] ?: 0
        if (have <= 0) return false
        stock[itemId] = have - 1
        consumed += itemId
        return true
    }
}

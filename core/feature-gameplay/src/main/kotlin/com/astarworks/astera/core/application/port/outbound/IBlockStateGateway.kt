package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.block.BlockDamageOutcome
import com.astarworks.astera.core.domain.block.BlockDamageProfile
import com.astarworks.astera.core.domain.block.BlockGatewayError
import com.astarworks.astera.core.domain.block.CustomBlockSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.BlockState
import com.astarworks.astera.core.domain.world.WorldId

/** World block I/O with one transient sparse HP store for both terrain and custom cover (ADR-0297). */
public interface IBlockStateGateway {
    /** Shared sparse damage store; terrain supplies its hardness-derived maximum. */
    public fun damagedWorlds(): Set<WorldId>
    public fun damageBlock(world: WorldId, pos: Vec3, maxHp: Int, amount: Int): Int
    public fun hasDamage(world: WorldId, pos: Vec3): Boolean
    public fun clearDamage(world: WorldId, pos: Vec3)
    public fun expireDamage(world: WorldId, currentTick: Long): List<Vec3>
    public fun clearWorldDamage(world: WorldId): List<Vec3>

    /** Current durability at [pos] in [world]; full HP is derived from world geometry for undamaged positions. */
    public fun getDurability(world: WorldId, pos: Vec3): Int

    /**
     * Skill-engine entrypoint for block damage (Wave 5x, ADR-0090).
     *
     * Resolves [profile] against the per-block-type [CustomBlockSpec]
     * (if registered) + the global [BlockDurabilityRule] to produce a
     * typed [BlockDamageOutcome]:
     *  - [BlockDamageOutcome.Damaged]: HP reduced, block intact
     *  - [BlockDamageOutcome.Broken]: HP hit 0, drops resolved per spec
     *  - [BlockDamageOutcome.Ineffective]: damage withheld
     *    (wrong tool, immune material, indestructible)
     *
     * Publishes `BlockDamagedBySkill` (Damaged outcome) or
     * `BlockBrokenBySkill` (Broken outcome) via the bus the gateway holds.
     * The Ineffective outcome publishes nothing — listeners learn from the
     * returned Result.
     */
    public fun applyDamageFromSkill(
        world: WorldId,
        pos: Vec3,
        skillId: SkillId,
        attackerId: PlayerId,
        profile: BlockDamageProfile,
    ): Result<BlockDamageOutcome, BlockGatewayError>

    /** Forget damage without changing world geometry. */
    public fun restore(world: WorldId, pos: Vec3)

    /** Place custom geometry at full HP, clear previous damage and publish CustomBlockPlaced. */
    public fun placeCustomBlock(
        world: WorldId,
        pos: Vec3,
        spec: CustomBlockSpec,
        placedBy: PlayerId,
    ): Result<BlockState, BlockGatewayError>

    /**
     * Whether a standing cover block (a block the registry knows, still holding HP) occupies the cell of [pos].
     * Area and ray skills (ADR-0280) test cells with this so they never hurt terrain or air.
     */
    public fun isCover(world: WorldId, pos: Vec3): Boolean

    /** Whether the cell of [pos] is empty, so a tool skill may place a temporary block there (ADR-0280). */
    public fun canPlaceAt(world: WorldId, pos: Vec3): Boolean

    /**
     * Places [spec] at [pos] like [placeCustomBlock], but as a temporary block of the running match: it is
     * removed again by [resetCover], and on Paper journaled so the arena repair restores what stood there.
     */
    public fun placeTemporaryBlock(
        world: WorldId,
        pos: Vec3,
        spec: CustomBlockSpec,
        placedBy: PlayerId,
    ): Result<BlockState, BlockGatewayError>

    /**
     * Rebuilds the cover of a finished match (ADR-0280): every cover block in the square of half-width
     * [halfExtent] around [centre] (the whole [world] when [centre] is `null`) is back at full HP and the
     * temporary blocks placed during the match are gone. Returns the positions that changed.
     */
    public fun resetCover(world: WorldId, centre: Vec3?, halfExtent: Double): List<Vec3>

    /**
     * Snapshot of damaged positions only; full and broken blocks occupy no HP entry.
     */
    public fun tracked(world: WorldId): Map<Vec3, BlockState>
}

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
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: per-position block durability state (Wave 5d → Wave 5x,
 * ADR-0090).
 *
 * Backs `BlockStateAutoRecoveryUseCase` (pseudo-recovery scheduler) and
 * `RestoreBlocksOnMatchEndedUseCase` (post-match revert). Phase 1 PoC uses
 * `InMemoryBlockStateGateway`; the Paper-side `PaperBlockStateGateway`
 * (Bukkit `BlockData` + NBT durability + Postgres flush) lands in Wave 6.
 *
 * Untracked positions return [BlockState.DEFAULT_MAX_DURABILITY] from
 * [getDurability]. [applyDamage] tracks the position if it wasn't already
 * tracked, then deducts the damage from current durability (or starts at
 * DEFAULT_MAX_DURABILITY if previously untracked). When durability falls to 0
 * the adapter is expected to publish a `BlockBroken` domain event (which the
 * BreakCore listener and the BlockChangeJournal both subscribe to).
 *
 * # Wave 5x additions (ADR-0090)
 *
 * - [applyDamageFromSkill]: skill engine entrypoint that resolves
 *   per-block-type [BlockDamageProfile] against the gateway, producing a
 *   typed [BlockDamageOutcome] (Damaged / Broken with drops / Ineffective).
 * - [placeCustomBlock]: materializes a [CustomBlockSpec] at a position,
 *   seeding the durability tracker with the spec's `baseDurability` as both
 *   current HP and max HP.
 */
public interface IBlockStateGateway {

    /** Current durability at [pos] in [world]; defaults to DEFAULT_MAX for untracked positions. */
    public fun getDurability(world: WorldId, pos: Vec3): Int

    /**
     * Apply [amount] damage to the block at [pos], modulated by the [tool]
     * tier (formula in `BlockDurabilityRule`). Returns the resulting
     * [BlockState] (durability clamped to 0..[BlockState.maxHp]).
     *
     * Wave 5d entrypoint — preserved for the listener-driven raw-damage
     * path (BreakCore, barrier hits). Skill-cast damage should use
     * [applyDamageFromSkill] instead so per-block-type tool effectiveness
     * matrices apply.
     */
    public fun applyDamage(
        world: WorldId,
        pos: Vec3,
        amount: Int,
        tool: ToolType,
    ): BlockState

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

    /** Restore [pos] to its tracked maxHp (used by post-match restoration + pseudo-recovery). */
    public fun restore(world: WorldId, pos: Vec3)

    /** Begin tracking [pos] with the supplied initial state (e.g. for placed blocks). */
    public fun track(world: WorldId, pos: Vec3, state: BlockState)

    /**
     * Materialize a custom block at [pos], replacing whatever was tracked
     * there (Wave 5x, ADR-0090). The gateway seeds a [BlockState] with
     * `durability = maxHp = spec.baseDurability`, records [placedBy], and
     * publishes a `CustomBlockPlaced` event.
     *
     * The world-side block swap (Bukkit `Block.type = spec.baseBukkitMaterial`)
     * is the Paper adapter's responsibility (Wave 6b) — the in-memory gateway
     * only owns the tracker state.
     */
    public fun placeCustomBlock(
        world: WorldId,
        pos: Vec3,
        spec: CustomBlockSpec,
        placedBy: PlayerId,
    ): Result<BlockState, BlockGatewayError>

    /**
     * Snapshot of every tracked position in [world] (used by
     * `BlockRecoveryScheduler` to iterate damaged blocks for periodic regen).
     */
    public fun tracked(world: WorldId): Map<Vec3, BlockState>
}

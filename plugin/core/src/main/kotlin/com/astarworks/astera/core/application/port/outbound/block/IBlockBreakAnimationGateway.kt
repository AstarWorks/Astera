package com.astarworks.astera.core.application.port.outbound.block

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.block.BlockBreakAnimationError
import com.astarworks.astera.core.domain.animation.block.BlockBreakStage
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: per-position block break-animation overlay
 * (Wave 5animation, ADR-0113).
 *
 * Companion to the Wave 5x [com.astarworks.astera.core.application.port.outbound.IBlockStateGateway]
 * — `IBlockStateGateway` owns the durability *state* (HP, max-HP, last-
 * damaged tick); this gateway owns the *visual overlay* (the 10-stage
 * cracked-block texture). They are deliberately separate ports because:
 *  1. State is per-position-permanent until restoration; animation is
 *     ephemeral and per-viewer-customisable.
 *  2. Skill damage doesn't always show a crack (instant-break skills
 *     skip stages); cracked overlays sometimes appear without state
 *     change (visual hint for mining HUD).
 *  3. Wave 6 wires the state gateway to NBT + Postgres; the animation
 *     gateway is a packet-only edge with no persistence.
 *
 * Phase 1 PoC ships `InMemoryBlockBreakAnimationGateway` + `FakeBlockBreakAnimationGateway`.
 * Wave 6 lands `PaperBlockBreakAnimationGateway` over
 * `BlockBreakAnimationPacket(entityId, blockPos, stage)`.
 *
 * # Per-viewer broadcasting
 *
 * `setBreakStage(..., broadcastViewers = null)` broadcasts to every
 * viewer in render distance (vanilla behaviour). Passing a non-null
 * `broadcastViewers` restricts the packet to that subset (team-only
 * mining indicators, anti-cheat shadow animations).
 *
 * # Stage transitions
 *
 * The gateway dedupes redundant calls: setting `STAGE_3` on a position
 * already at `STAGE_3` returns Success without publishing
 * [com.astarworks.astera.core.domain.event.BlockBreakStageChanged]. The Wave 5j
 * mining HUD calls `setBreakStage` per tick; the dedup keeps the bus
 * volume manageable.
 */
public interface IBlockBreakAnimationGateway {

    /**
     * Set the break animation stage at [position] in [world]. If [stage]
     * equals the currently-tracked stage, the call is a no-op and
     * returns Success without an event publish. Otherwise the gateway
     * updates the tracker, publishes
     * [com.astarworks.astera.core.domain.event.BlockBreakStageChanged], and
     * (in Wave 6) sends `BlockBreakAnimationPacket` to viewers.
     *
     * Passing [BlockBreakStage.RESET] is equivalent to calling
     * [cancelBreakAnimation]: it removes the tracker entry and publishes
     * with `newStage = RESET`.
     *
     * @param broadcastViewers if non-null, restricts the packet to this
     *   player subset (per-viewer animation). If null, broadcasts to all
     *   viewers in render distance.
     */
    public fun setBreakStage(
        world: WorldId,
        position: Vec3,
        stage: BlockBreakStage,
        broadcastViewers: Set<PlayerId>? = null,
    ): Result<Unit, BlockBreakAnimationError>

    /**
     * Clear any active break animation at [position]. Idempotent:
     * clearing an untracked position returns Success and does not
     * publish an event.
     */
    public fun cancelBreakAnimation(
        world: WorldId,
        position: Vec3,
    ): Result<Unit, BlockBreakAnimationError>

    /**
     * Current tracked stage at [position], or `null` if untracked.
     * Used by tests and the mining HUD for stage-change detection.
     */
    public fun getCurrentStage(
        world: WorldId,
        position: Vec3,
    ): BlockBreakStage?
}

package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Mining-progress lifecycle: player started mining a block (Wave 5animation, ADR-0113).
 *
 * Published by `IMiningProgressDisplay` impls (or use-case orchestrators
 * around them) on the first per-tick update for a new (player, block)
 * pair. Counterpart of [MiningCompleted] (block broke) / [MiningCancelled]
 * (player switched targets / released left-click without breaking).
 *
 * Subscribers:
 *  - Quest progress (Wave 5p) — "mine 100 stone blocks" objectives.
 *  - Stats (Phase 4) — per-tool mining counters.
 *  - Replay capture (Wave 5w) — distinguishes mining attempts from
 *    completed breaks.
 *
 * @property playerId the miner.
 * @property world world id.
 * @property blockPosition block being mined.
 * @property toolType the player's active tool.
 * @property atTick server tick at start time.
 */
public data class MiningStarted(
    public val playerId: PlayerId,
    public val world: WorldId,
    public val blockPosition: Vec3,
    public val toolType: ToolType,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}

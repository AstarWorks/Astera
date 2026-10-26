package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.animation.mining.MiningCancelReason
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Mining-progress lifecycle: player abandoned mining without breaking the block.
 *
 * Covers all "stopped mining before block broke" cases (Wave 5animation, ADR-0113):
 *  - Player released left-click.
 *  - Player switched targets to another block.
 *  - Player switched to a tool the block won't accept (mining stops).
 *  - Player moved out of break range.
 *  - Match phase change disabled the mine (cinematic intro, frozen camera).
 *
 * The [reason] discriminator lets subscribers tell apart "the player gave
 * up" from "the system stopped them". Sealed (not enum) so future
 * variants can carry payload without breaking exhaustive callers.
 *
 * Subscribers:
 *  - Mining progress display — clear the HUD.
 *  - Block break animation gateway — call `cancelBreakAnimation`.
 *  - Stats (Phase 4) — distinguish abandoned vs completed mines for
 *    completion-rate metrics.
 *
 * @property playerId the miner.
 * @property world world id.
 * @property blockPosition block that was being mined.
 * @property reason classification of why mining stopped.
 * @property atTick server tick at cancel time.
 */
public data class MiningCancelled(
    public val playerId: PlayerId,
    public val world: WorldId,
    public val blockPosition: Vec3,
    public val reason: MiningCancelReason,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}

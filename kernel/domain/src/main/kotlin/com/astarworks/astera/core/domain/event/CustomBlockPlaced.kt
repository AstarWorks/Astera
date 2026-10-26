package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.BlockType
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain event: a [CustomBlockSpec][com.astarworks.astera.core.domain.block.CustomBlockSpec]
 * was materialized at a world position (Wave 5x, ADR-0090).
 *
 * Published by `IBlockStateGateway.placeCustomBlock` once the gateway has
 * seeded its tracker. Wave 6b's `PaperCustomBlockAdapter` subscribes to
 * perform the world-side `Block.type` swap; the persistence flush also
 * subscribes so the new block hits durable storage promptly.
 *
 * Distinct from Wave 5d's `BlockPlaced` event — `BlockPlaced` is
 * match-scoped (`matchId` field, journal-bound) and fires for any block
 * placement going through the per-match journal. `CustomBlockPlaced` is
 * **always** a custom-block-spec placement (vanilla placements are not
 * routed through here) and is world-scoped, suitable for the open world.
 */
public data class CustomBlockPlaced(
    val world: WorldId,
    val position: Vec3,
    val blockType: BlockType,
    val placedBy: PlayerId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(placedBy.uuid)
}

package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain-level "a block was broken" event.
 *
 * Wave 5c introduced this as a Match-plugin stub for the BreakCore listener.
 * Wave 5d promotes it to Core alongside [BlockPlaced] so block tracking
 * (`IBlockStateGateway`), per-match journaling, and the BreakCore listener
 * all consume the same vendor-neutral event from a stable package.
 *
 * The real **producer** (Bukkit `BlockBreakEvent` → adapter) lands in Wave 6
 * via `BukkitEventAdapter` per `RTM-PARITY-TRACKER.md` Category 3. Until then
 * this event is published by `BlockStateAutoRecoveryUseCase` (durability ≤ 0)
 * and by tests.
 *
 * `blockId` is a namespaced identifier (e.g. `minecraft:obsidian`,
 * `astera:core_block`) — vendor-neutral, no Bukkit `Material` enum at this
 * layer (ADR-0001 / 0002).
 */
public data class BlockBroken(
    val matchId: MatchId,
    val playerId: PlayerId,
    val position: Vec3,
    val blockId: String,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}

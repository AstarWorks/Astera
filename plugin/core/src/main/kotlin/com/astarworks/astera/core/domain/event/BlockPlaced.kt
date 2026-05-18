package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain-level "a block was placed" event.
 *
 * Companion to [BlockBroken]. Together they form the two sides of the block
 * tracking journal (`BlockChangeJournal` subscribes to both; restoration
 * replays them on `MatchEnded`).
 *
 * The real **producer** (Bukkit `BlockPlaceEvent` → adapter) lands in Wave 6
 * via `BukkitEventAdapter` extension. Until then this event is published by
 * tests and (eventually) by use cases that programmatically place blocks
 * (e.g. `CreateBarrierUseCase` Wave 5d).
 *
 * `blockId` is a namespaced identifier (e.g. `minecraft:obsidian`,
 * `astera:barrier_block`) — vendor-neutral, no Bukkit `Material` at this
 * layer (ADR-0001 / 0002).
 */
public data class BlockPlaced(
    val matchId: MatchId,
    val playerId: PlayerId,
    val position: Vec3,
    val blockId: String,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

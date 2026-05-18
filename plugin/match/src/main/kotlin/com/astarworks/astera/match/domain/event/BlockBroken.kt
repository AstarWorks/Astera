package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain-level "a block was broken" placeholder event (Wave 5c).
 *
 * Wave 5c uses this so the BreakCore game-mode listener can subscribe to a
 * stable domain event and unit-tests can publish it directly. The real
 * **producer** (Bukkit `BlockBreakEvent` → adapter) lands in Wave 5d/6 per
 * `RTM-PARITY-TRACKER.md` Category 3. Until then this event is published only
 * by tests.
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
) : DomainEvent

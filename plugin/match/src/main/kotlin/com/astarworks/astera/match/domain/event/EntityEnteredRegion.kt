package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Domain-level "entity entered a named region" placeholder event (Wave 5c).
 *
 * Subscribed by CarryTNT (delivery zone), CaptureWool (control area), and
 * StealJewelry (gem deposit zone) listeners. Real producer (Bukkit player-move
 * → adapter with stage region lookup) lands in Wave 5d.
 *
 * `regionId` is a stable identifier defined inside the stage YAML
 * (e.g. `red_base`, `wool_zone_a`, `gem_deposit_blue`).
 */
public data class EntityEnteredRegion(
    val matchId: MatchId,
    val playerId: PlayerId,
    val regionId: String,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

/**
 * Companion event published when an entity leaves a previously-entered region.
 *
 * CaptureWool uses both sides to detect "still standing in zone" via
 * scheduler-driven progress accumulation. Producer same as
 * [EntityEnteredRegion].
 */
public data class EntityLeftRegion(
    val matchId: MatchId,
    val playerId: PlayerId,
    val regionId: String,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

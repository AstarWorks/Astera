package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain-level "the player walked / ran / fell from one position to another"
 * event (Wave 5world-events, ADR-0111).
 *
 * Designed for **region boundary triggers**: the Wave 5n region permission
 * service subscribes here, compares [from] / [to] against indexed regions,
 * and fires `RegionEntered` / `RegionExited` derived events when the player
 * crosses a boundary. The anticheat layer (Wave 5q) also reads this for
 * sliding-window movement validation.
 *
 * Distinct from [PlayerTeleported] because [PlayerMoved] models *physical*
 * movement (the player actually traversed space) — a teleport is a discrete
 * jump and carries different semantics for triggers / anticheat / replay.
 *
 * The producer (Bukkit `PlayerMoveEvent` → adapter) lands in Wave 6 via
 * `BukkitEventAdapter`. Until then this event is published by tests + use
 * cases that programmatically move players. Adapters are expected to
 * *throttle* the publication rate (canonical: 1 publish per moved chunk OR
 * per 0.5 block) to avoid event bus spam on continuous walking.
 */
public data class PlayerMoved(
    val playerId: PlayerId,
    val from: Vec3,
    val to: Vec3,
    val world: WorldId,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.TeleportReason
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Domain-level "the player teleported (discrete jump) from one position to
 * another" event (Wave 5world-events, ADR-0111).
 *
 * Distinct from [PlayerMoved] because a teleport is *not* physical movement:
 * the player skips intermediate space, and downstream consumers (anticheat,
 * region triggers, replay capture) need to apply the move without inferring
 * a continuous path. [reason] disambiguates the cause so anticheat can
 * exempt command / admin / respawn teleports and replay capture can render
 * the right transition style.
 *
 * Cross-world teleports are represented with distinct [worldFrom] /
 * [worldTo] — subscribers that only care about same-world moves can short-
 * circuit when the two differ. The producer (Bukkit `PlayerTeleportEvent`
 * → adapter) lands in Wave 6.
 */
public data class PlayerTeleported(
    val playerId: PlayerId,
    val from: Vec3,
    val to: Vec3,
    val worldFrom: WorldId,
    val worldTo: WorldId,
    val reason: TeleportReason,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}

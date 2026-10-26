package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A player stepped onto a queue lane pad of the sky-island lobby (F3-1). Fired on entry only,
 * never while standing and never on leaving the pad. The Match plugin turns it into a queue join.
 */
public data class LobbyLanePadEntered(
    val playerId: PlayerId,
    /** Lane id as in `content/matchmaking/queue.yaml`. */
    val laneId: String,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}

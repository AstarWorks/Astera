package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.domain.event.MatchFormed
import com.astarworks.astera.core.domain.event.MatchQueueJoined
import com.astarworks.astera.core.domain.event.MatchQueueLeft
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Writes the queue's life to the server log at INFO (ADR-0248), whichever path caused it (menu, pad, command,
 * quit): join, leave (with the reason tag) and formed roster. Together with the scheduler / former lines this
 * lets someone reading the log reconstruct what happened to a player who "joined and nothing happened".
 */
public class QueueActivityLogger(private val nameOf: (PlayerId) -> String?) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun onJoined(event: MatchQueueJoined) {
        log.info(
            "Queue join: {} ({}) -> {}{}",
            label(event.playerId), event.playerId.uuid, event.modeKey,
            event.partyId?.let { " party=$it" } ?: "",
        )
    }

    public fun onLeft(event: MatchQueueLeft) {
        log.info("Queue leave: {} ({}) <- {} ({})", label(event.playerId), event.playerId.uuid, event.modeKey, event.reasonTag)
    }

    public fun onFormed(event: MatchFormed) {
        log.info(
            "Queue formed: {} players in {} teams for {} [{}]",
            event.players.size, event.teams.size, event.modeKey, event.players.joinToString { label(it) },
        )
    }

    private fun label(id: PlayerId): String = nameOf(id) ?: id.uuid.toString().take(8)
}

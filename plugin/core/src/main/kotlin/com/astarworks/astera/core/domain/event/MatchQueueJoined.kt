package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import java.time.Instant

/**
 * Player joined a matchmaking queue (Wave 5w / ADR-0089).
 *
 * `modeKey` is the matchmaking mode's wire-string representation
 * (`"Norma" | "Custom(<gameModeId>)" | "Tournament(<tournamentMatchId>)"`).
 * Carrying it as a String keeps the event Core-resident: Core cannot import
 * `match.domain.matchmaking.MatchmakingMode` (sibling-isolation), but lobby
 * HUD coordinators in the Match plugin can parse it back on subscribe.
 */
public data class MatchQueueJoined(
    val playerId: PlayerId,
    val modeKey: String,
    val partyId: PartyId? = null,
    val enqueuedAt: Instant,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * `IMatchmaker.tryFormMatch` resolved a complete roster (Wave 5w / ADR-0089).
 *
 * The downstream `MatchFoundUseCase` then invokes `StartMatchUseCase` and
 * teleports players to spawn (Wave 5c flow). HUD coordinators subscribe to
 * push "Match found! Teleporting…" to each involved player.
 *
 * `teams` is the partition of `players` selected by the matchmaker
 * (party-aware: party members in the same `Set<PlayerId>`).
 */
public data class MatchFormed(
    val players: List<PlayerId>,
    val teams: List<Set<PlayerId>>,
    val modeKey: String,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global

    init {
        val flattened = teams.flatten()
        require(flattened.toSet() == players.toSet()) {
            "MatchFormed.teams must partition players exactly. players=$players teams=$teams"
        }
    }
}

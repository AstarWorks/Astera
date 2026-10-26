package com.astarworks.astera.core.application.service.presence

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.presence.IRichPresenceGateway
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.presence.RichPresenceState
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 5t (ADR-0086) — bridges match / session domain events to the rich
 * presence gateway. Subscribes to:
 *
 *   - `PlayerJoinedServer` → `InLobby(serverId)`
 *   - `MatchStarted`        → starts tracking the match; players are added
 *     to the InMatch state via [trackParticipant]
 *   - `MatchEnded`          → flips every tracked participant back to InLobby
 *
 * The Phase 1 [MatchStarted] domain event does not carry participant ids
 * (Wave 5c-era simple shape) so this listener exposes [trackParticipant] for
 * the match service to call at team-join time, mirroring how the audit log
 * listener handles the same gap. Wave 6a may extend the event with a list
 * if richer subscribers grow.
 */
public class SessionLifecycleRichPresenceListener(
    private val gateway: IRichPresenceGateway,
    private val serverId: String = DEFAULT_SERVER_ID,
) {
    private val participants: MutableMap<MatchId, MutableSet<PlayerId>> = ConcurrentHashMap()

    public fun bind(eventBus: IDomainEventBus): List<IDomainEventBus.Subscription> {
        val subs = mutableListOf<IDomainEventBus.Subscription>()

        subs += eventBus.subscribe(PlayerJoinedServer::class, EventDelivery.Global) { ev ->
            gateway.publish(ev.playerId, RichPresenceState.InLobby(serverId))
        }

        subs += eventBus.subscribe(MatchStarted::class, EventDelivery.Global) { _ ->
            // Wave 5t scope: no per-event participant list yet. Player
            // states flip via trackParticipant when team-join happens.
        }

        subs += eventBus.subscribe(MatchEnded::class, EventDelivery.Global) { ev ->
            participants.remove(ev.matchId)?.forEach { playerId ->
                gateway.publish(playerId, RichPresenceState.InLobby(serverId))
            }
        }

        return subs
    }

    /**
     * Track [playerId] as a participant of [matchId] and immediately push
     * `InMatch(matchId, mode, 0, 0)` to the gateway. Called by match
     * use-cases on team-join.
     */
    public fun trackParticipant(matchId: MatchId, playerId: PlayerId, mode: String) {
        participants.computeIfAbsent(matchId) { ConcurrentHashMap.newKeySet() }.add(playerId)
        gateway.publish(
            playerId,
            RichPresenceState.InMatch(
                matchId = matchId.value,
                mode = mode,
                kills = 0,
                deaths = 0,
            ),
        )
    }

    public companion object {
        public const val DEFAULT_SERVER_ID: String = "astera"
    }
}

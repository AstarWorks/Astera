package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import org.slf4j.LoggerFactory

/**
 * Wave M 5polish-quickwin (ADR-0130 §E) — refreshes the "N players online"
 * sidebar HUD for every lobby player whenever someone joins the server.
 *
 * # Why a separate listener
 *
 * Wave 5w's [com.astarworks.astera.match.application.service.hud.LobbyWaitingRoomHud]
 * already maintains a per-player matchmaking sidebar — that is a queue
 * concern. This listener is a separate concern: the server-wide population
 * counter, visible in lobby for every player regardless of queue state.
 * Splitting keeps each subscriber narrow and lets either side be swapped
 * independently.
 *
 * # Filtering target audience
 *
 * Only [SessionState.Lobby] players receive the update. In-match players
 * have their own HUD (game-mode-specific sidebar); spectators / dead /
 * loading sessions are ignored so the count display does not clobber their
 * existing overlays. The same rule applies in
 * [UpdatePopulationHudOnPlayerQuitListener].
 *
 * # Phase 1 PoC scope
 *
 * - No per-locale string composition beyond the
 *   [com.astarworks.astera.core.domain.i18n.MessageKey] placeholder — the
 *   `{count}` token is replaced by `IMessageRenderer` at the GUI adapter
 *   edge.
 * - InMemoryHudGateway records each call + publishes `HudShown`; Wave 6b
 *   Paper adapter carries the payload through to actual Scoreboard frames.
 *
 * Subscribe via the [subscribe] factory which the Phase C wiring calls
 * from `AsteraCorePlugin.onEnable`. Idempotent if called twice (per
 * `IDomainEventBus.subscribe` contract).
 */
public class UpdatePopulationHudOnPlayerJoinedListener(
    private val sessions: IPlayerSessionRepository,
    private val hud: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: PlayerJoinedServer) {
        broadcastPopulationHud(sessions, hud, log, triggerPlayer = event.playerId)
    }
}

/**
 * Wave M 5polish-quickwin (ADR-0130 §E) — twin of the join listener; same
 * sidebar refresh fired on quit so the count decrements visibly for every
 * lobby player.
 */
public class UpdatePopulationHudOnPlayerQuitListener(
    private val sessions: IPlayerSessionRepository,
    private val hud: IHudGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: com.astarworks.astera.core.domain.event.PlayerQuitServer) {
        broadcastPopulationHud(sessions, hud, log, triggerPlayer = event.playerId)
    }
}

/**
 * Recomputes the active-session count and pushes a sidebar HUD to every
 * lobby player. Excludes [triggerPlayer] for the *join* path (their session
 * row may not be upserted yet by the time the join listener fires) and
 * unconditionally excludes [SessionState.Disconnected] sessions from both
 * the count and the broadcast target.
 *
 * Top-level helper so both listeners share the same projection without
 * either depending on the other.
 */
private fun broadcastPopulationHud(
    sessions: IPlayerSessionRepository,
    hud: IHudGateway,
    log: org.slf4j.Logger,
    triggerPlayer: PlayerId,
) {
    val active = sessions.all().filter { it.state != SessionState.Disconnected }
    val count = active.size
    val layout = HudLayout.Sidebar(
        titleKey = MSG_TITLE,
        lines = listOf(
            HudComponent.Text(messageKey = MSG_LINE),
        ),
    )
    for (session in active) {
        if (session.state != SessionState.Lobby) continue
        val result = hud.showFor(session.playerId, layout)
        if (result is Result.Failure) {
            log.debug(
                "Population HUD push failed for {} (count={}, trigger={}): {}",
                session.playerId, count, triggerPlayer, result.error,
            )
        }
    }
}

private val MSG_TITLE = MessageKey("astera.population.online.title")
private val MSG_LINE = MessageKey("astera.population.online.line")

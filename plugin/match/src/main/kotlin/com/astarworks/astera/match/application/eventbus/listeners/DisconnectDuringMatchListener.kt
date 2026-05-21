package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.match.application.usecase.match.ApplyForfeitUseCase
import com.astarworks.astera.match.application.usecase.match.RemoveFromMatchUseCase
import com.astarworks.astera.match.application.usecase.match.RemoveReason
import com.astarworks.astera.match.domain.event.PlayerDisconnectedFromMatch
import org.slf4j.LoggerFactory

/**
 * Reacts to [PlayerQuitServer] for participants of an in-flight match
 * (Wave 5h, ADR-0079 §6).
 *
 * Per-phase policy:
 *
 * - [MatchPhase.COUNTDOWN] — kick immediately via [RemoveFromMatchUseCase];
 *   the player has not committed to the match, so the seat re-opens for the
 *   next queue cycle.
 * - [MatchPhase.ACTIVE] — flip session state to [SessionState.Disconnected],
 *   schedule a 3-min reconnect-window timeout, and publish
 *   [PlayerDisconnectedFromMatch] with `willTimeoutAtTick` populated so HUD
 *   subscribers can render a countdown. If the player rejoins within the
 *   window, `ReconnectDuringMatchListener` upgrades the session back to
 *   [SessionState.InMatch] and the timeout-fired remove is a no-op
 *   (the re-check inside the scheduled task observes a state ≠ Disconnected).
 * - [MatchPhase.SUDDEN_DEATH] — escalate to forfeit via [ApplyForfeitUseCase].
 *   The reconnect window is suppressed because the match's final stretch
 *   cannot tolerate the pause.
 * - [MatchPhase.WAITING] / [MatchPhase.ENDED] — no-op: pre-match queue
 *   bookkeeping owns WAITING, and ENDED matches have nothing left to lose.
 *
 * Non-participant disconnects (no session, no currentMatch, or unknown match)
 * are silently dropped: this listener is intentionally scoped to the
 * "player was in a match and dropped" path.
 */
public class DisconnectDuringMatchListener(
    private val sessions: IPlayerSessionRepository,
    private val matches: IMatchRepository,
    private val removeFromMatch: RemoveFromMatchUseCase,
    private val applyForfeit: ApplyForfeitUseCase,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    private val log = LoggerFactory.getLogger(DisconnectDuringMatchListener::class.java)

    public fun handle(event: PlayerQuitServer) {
        val session = sessions.find(event.playerId) ?: return
        val matchId = session.currentMatch ?: return
        val match = matches.findById(matchId) ?: return

        when (match.phase) {
            MatchPhase.COUNTDOWN -> {
                removeFromMatch.remove(
                    playerId = event.playerId,
                    matchId = matchId,
                    reason = RemoveReason.Disconnect,
                ).onFailure { err ->
                    log.debug(
                        "DisconnectDuringMatchListener: remove failed (COUNTDOWN) player={} match={} error={}",
                        event.playerId, matchId, err,
                    )
                }
            }

            MatchPhase.ACTIVE -> {
                val nowTick = scheduler.currentTick()
                val timeoutAtTick = nowTick + RECONNECT_WINDOW_TICKS

                // Flip to Disconnected first so a fast reconnect observes the
                // right state when the rejoin path checks it.
                sessions.upsert(session.copy(state = SessionState.Disconnected))

                // Schedule the timeout-driven removal. The re-check is essential:
                // a player who rejoins within the window will have their state
                // upgraded back to InMatch by ReconnectDuringMatchListener, and
                // the timeout closure must respect that.
                scheduler.runLaterTicks(RECONNECT_WINDOW_TICKS) {
                    val current = sessions.find(event.playerId) ?: return@runLaterTicks
                    if (current.state == SessionState.Disconnected && current.currentMatch == matchId) {
                        removeFromMatch.remove(
                            playerId = event.playerId,
                            matchId = matchId,
                            reason = RemoveReason.Disconnect,
                        ).onFailure { err ->
                            log.debug(
                                "DisconnectDuringMatchListener: timeout-remove failed player={} match={} error={}",
                                event.playerId, matchId, err,
                            )
                        }
                    }
                }

                eventBus.publish(
                    PlayerDisconnectedFromMatch(
                        playerId = event.playerId,
                        matchId = matchId,
                        phase = MatchPhase.ACTIVE,
                        willTimeoutAtTick = timeoutAtTick,
                        atTick = nowTick,
                    ),
                )
            }

            MatchPhase.SUDDEN_DEATH -> {
                applyForfeit.apply(event.playerId, matchId).onFailure { err ->
                    log.debug(
                        "DisconnectDuringMatchListener: forfeit failed (SUDDEN_DEATH) player={} match={} error={}",
                        event.playerId, matchId, err,
                    )
                }
            }

            MatchPhase.ENDED,
            MatchPhase.WAITING,
            -> {
                // No-op: pre-match queue / post-match cleanup own these phases.
            }
        }
    }

    private companion object {
        /** 3 minutes @ 20 tps = 3600 ticks. ADR-0079 §6 reconnect window. */
        const val RECONNECT_WINDOW_TICKS: Long = 3600L
    }
}

package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerLeavingForLobby
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.match.application.usecase.match.ApplyForfeitUseCase
import com.astarworks.astera.match.application.usecase.match.RemoveFromMatchUseCase
import com.astarworks.astera.match.application.usecase.match.RemoveReason
import com.astarworks.astera.match.domain.event.PlayerDisconnectedFromMatch
import org.slf4j.LoggerFactory

/**
 * Reacts to a participant leaving an in-flight match: [PlayerQuitServer] (connection drop) and
 * [PlayerLeavingForLobby] (`/astera travel lobby`, the travel menu) are the same thing here
 * (Wave 5h, ADR-0079 §6; MODE-14).
 *
 * Per-phase policy:
 *
 * - [MatchPhase.COUNTDOWN] — kick immediately via [RemoveFromMatchUseCase];
 *   the player has not committed to the match, so the seat re-opens for the
 *   next queue cycle.
 * - [MatchPhase.ACTIVE] — the stars they carry burst where they stand ([onLeaving]), the session flips to
 *   [SessionState.Disconnected], and a [reconnectWindowTicks] (60 s) window starts; [PlayerDisconnectedFromMatch]
 *   carries `willTimeoutAtTick` for the HUD countdown and the lobby's reconnect route. Back within the window,
 *   `ReconnectDuringMatchListener` returns them to the same match and team with their Norma level and stats.
 *   When the window runs out they forfeit via [ApplyForfeitUseCase]: out of the roster, no win rewards,
 *   a loss on the record, and the match goes on (or ends when their team is empty).
 * - [MatchPhase.SUDDEN_DEATH] — stars burst and the player forfeits at once; the final stretch cannot tolerate the pause.
 * - [MatchPhase.WAITING] / [MatchPhase.ENDED] — no-op: pre-match queue
 *   bookkeeping owns WAITING, and ENDED matches have nothing left to lose.
 *
 * Non-participant events (no claim and not on a live roster, or unknown match) are silently dropped.
 */
public class DisconnectDuringMatchListener(
    private val sessions: IPlayerSessionRepository,
    private val matches: IMatchRepository,
    private val removeFromMatch: RemoveFromMatchUseCase,
    private val applyForfeit: ApplyForfeitUseCase,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    /** Runs while the leaver still stands where they were (before any teleport): their carried stars burst there. */
    private val onLeaving: (MatchId, PlayerId) -> Unit = { _, _ -> },
    private val reconnectWindowTicks: Long = RECONNECT_WINDOW_TICKS,
) {

    private val log = LoggerFactory.getLogger(DisconnectDuringMatchListener::class.java)

    public fun handle(event: PlayerQuitServer) {
        leave(event.playerId)
    }

    /** `/astera travel lobby` from a running match behaves like a dropped connection. */
    public fun onLeavingForLobby(event: PlayerLeavingForLobby) {
        leave(event.playerId)
    }

    private fun leave(playerId: PlayerId) {
        val session = sessions.find(playerId)
        // Sessions are not flipped to InMatch on entry (the match repository is the source of truth for
        // membership), so a roster member of a running match counts even without a session claim.
        val matchId = session?.currentMatch
            ?: matches.findMatchOf(playerId)?.takeIf { it.phase == MatchPhase.ACTIVE || it.phase == MatchPhase.SUDDEN_DEATH }?.id
            ?: return
        val match = matches.findById(matchId) ?: return
        // Already counted as away (a lobby command followed by a quit): one window, one burst.
        if (session != null && session.state is SessionState.Disconnected && session.currentMatch == matchId) return

        when (match.phase) {
            MatchPhase.COUNTDOWN -> {
                removeFromMatch.remove(
                    playerId = playerId,
                    matchId = matchId,
                    reason = RemoveReason.Disconnect,
                ).onFailure { err ->
                    log.debug(
                        "DisconnectDuringMatchListener: remove failed (COUNTDOWN) player={} match={} error={}",
                        playerId, matchId, err,
                    )
                }
            }

            MatchPhase.ACTIVE -> {
                onLeaving(matchId, playerId)

                val nowTick = scheduler.currentTick()
                val timeoutAtTick = nowTick + reconnectWindowTicks

                // Flip to Disconnected first so a fast reconnect observes the
                // right state when the rejoin path checks it.
                sessions.upsert(
                    (session ?: PlayerSession(playerId = playerId, state = SessionState.Disconnected))
                        .copy(state = SessionState.Disconnected, currentMatch = matchId),
                )

                // Schedule the forfeit. The re-check is essential: a player who rejoins within the
                // window will have their state upgraded back to InMatch by ReconnectDuringMatchListener,
                // and the timeout closure must respect that.
                scheduler.runLaterTicks(reconnectWindowTicks) {
                    val current = sessions.find(playerId) ?: return@runLaterTicks
                    if (current.state == SessionState.Disconnected && current.currentMatch == matchId) {
                        applyForfeit.apply(playerId, matchId).onFailure { err ->
                            log.debug(
                                "DisconnectDuringMatchListener: timeout-forfeit failed player={} match={} error={}",
                                playerId, matchId, err,
                            )
                        }
                    }
                }

                eventBus.publish(
                    PlayerDisconnectedFromMatch(
                        playerId = playerId,
                        matchId = matchId,
                        phase = MatchPhase.ACTIVE,
                        willTimeoutAtTick = timeoutAtTick,
                        atTick = nowTick,
                    ),
                )
            }

            MatchPhase.SUDDEN_DEATH -> {
                onLeaving(matchId, playerId)
                applyForfeit.apply(playerId, matchId).onFailure { err ->
                    log.debug(
                        "DisconnectDuringMatchListener: forfeit failed (SUDDEN_DEATH) player={} match={} error={}",
                        playerId, matchId, err,
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

    /** A finished match leaves nothing to come back to: players still marked as dropped from it are lobby players again. */
    public fun onMatchEnded(event: MatchEnded) {
        val members = matches.findById(event.matchId)?.teams?.flatMap { it.members } ?: return
        for (member in members) {
            val session = sessions.find(member) ?: continue
            if (session.state is SessionState.Disconnected && session.currentMatch == event.matchId) {
                sessions.upsert(session.copy(state = SessionState.Lobby, currentMatch = null))
            }
        }
    }

    public companion object {
        /** 60 s @ 20 tps. A dropped participant may come back to their match and team within this window (MODE-14). */
        public const val RECONNECT_WINDOW_TICKS: Long = 1200L
    }
}

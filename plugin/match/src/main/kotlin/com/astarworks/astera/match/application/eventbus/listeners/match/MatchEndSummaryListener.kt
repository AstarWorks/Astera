package com.astarworks.astera.match.application.eventbus.listeners.match

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * On [MatchEnded], broadcast a short result summary to every
 * participant (Wave 5game-complete, ADR-0121 §D).
 *
 * # What participants see
 *
 *  1. **Subtitle** — per-player verdict:
 *     - Player on `winningTeam` → `astera.match.summary.victory` (key
 *       resolved to "Victory!" by the i18n catalogue).
 *     - Player on a losing team → `astera.match.summary.defeat`.
 *     - `winningTeam == null` (draw) → `astera.match.summary.draw`.
 *  2. **Chat lines** — `astera.match.summary.header` followed by
 *     `astera.match.summary.reason.<reason>` so the reason key maps
 *     cleanly without listener-side string formatting.
 *
 * # Phase 1 PoC scope
 *
 * GUI summary screen (MVP / kills / damage breakdown) is **deferred to
 * Wave 6b / Phase 4**. The chat + subtitle path covers the "did I win,
 * and why did the match end?" surface that every PvP server has to
 * ship. MVP-aware extension lands once `StatsAggregator` (Wave 5stats)
 * exposes per-match summaries on the bus.
 *
 * # Cross-cutting
 *
 * `RewardOnMatchEndedListener` (stats + economy plugins) and
 * `TeleportToLobbyOnMatchEndedListener` (Wave 5lobby) also subscribe to
 * `MatchEnded`. Order is irrelevant — those listeners mutate state,
 * this listener only renders. Subscriber-ordering Konsist rule isn't
 * required because no listener depends on the summary having rendered.
 *
 * # Failure mode
 *
 * Match unknown → log at INFO and drop (could happen if the match was
 * already destroyed by a concurrent listener; defensive).
 * Per-player HUD or chat failures log at DEBUG and continue.
 */
public class MatchEndSummaryListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        val match = matches.findById(event.matchId) ?: run {
            log.info(
                "MatchEndSummary: match {} not found in repo at MatchEnded time; skipping.",
                event.matchId,
            )
            return
        }

        for (team in match.teams) {
            val verdict = verdictKey(team.id, event.winningTeam)
            for (member in team.members) {
                pushSubtitle(member, verdict, event.matchId)
                pushChatSummary(member, event)
            }
        }
    }

    private fun verdictKey(team: TeamId, winningTeam: TeamId?): MessageKey = when {
        winningTeam == null -> MSG_DRAW
        winningTeam == team -> MSG_VICTORY
        else -> MSG_DEFEAT
    }

    private fun pushSubtitle(playerId: PlayerId, verdict: MessageKey, matchId: MatchId) {
        val layout = HudLayout.Subtitle(
            component = HudComponent.Text(messageKey = verdict),
            fadeInTicks = SUMMARY_FADE_IN,
            stayTicks = SUMMARY_STAY,
            fadeOutTicks = SUMMARY_FADE_OUT,
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "MatchEndSummary subtitle failed for {} in match {}: {}",
                playerId, matchId, outcome.error,
            )
        }
    }

    private fun pushChatSummary(playerId: PlayerId, event: MatchEnded) {
        sendOrLog(playerId, MSG_HEADER, emptyMap(), event.matchId)
        sendOrLog(
            playerId,
            MessageKey("astera.match.summary.reason.${reasonKindOf(event)}"),
            emptyMap(),
            event.matchId,
        )
    }

    private fun sendOrLog(
        playerId: PlayerId,
        key: MessageKey,
        params: Map<String, String>,
        @Suppress("UNUSED_PARAMETER") matchId: MatchId,
    ) {
        val rendered = messages.render(playerId, key, params)
        // sendMessage is expected to no-op for offline recipients; the
        // adapter logs at TRACE on miss. We don't catch JVM throwables
        // here — Konsist forbids raw throwables in application layers.
        playerGateway.sendMessage(playerId, rendered)
    }

    /**
     * Stable lowercase snake_case discriminator for the reason — kept
     * in this listener so i18n key naming doesn't leak into [com.astarworks.astera.core.domain.event.EndReason].
     */
    private fun reasonKindOf(event: MatchEnded): String =
        when (event.reason) {
            com.astarworks.astera.core.domain.event.EndReason.ScoreThresholdReached -> "score-threshold"
            com.astarworks.astera.core.domain.event.EndReason.TimerExpired -> "timer-expired"
            com.astarworks.astera.core.domain.event.EndReason.AdminEnded -> "admin"
            com.astarworks.astera.core.domain.event.EndReason.SuddenDeathResolved -> "sudden-death"
            com.astarworks.astera.core.domain.event.EndReason.ObjectiveCompleted -> "objective"
            is com.astarworks.astera.core.domain.event.EndReason.Cancelled -> "cancelled"
        }

    public companion object {
        public val MSG_VICTORY: MessageKey = MessageKey("astera.match.summary.victory")
        public val MSG_DEFEAT: MessageKey = MessageKey("astera.match.summary.defeat")
        public val MSG_DRAW: MessageKey = MessageKey("astera.match.summary.draw")
        public val MSG_HEADER: MessageKey = MessageKey("astera.match.summary.header")

        public const val SUMMARY_FADE_IN: Int = 10
        public const val SUMMARY_STAY: Int = 80
        public const val SUMMARY_FADE_OUT: Int = 20
    }
}

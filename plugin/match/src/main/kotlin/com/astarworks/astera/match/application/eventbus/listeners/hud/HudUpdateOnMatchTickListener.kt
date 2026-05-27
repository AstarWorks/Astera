package com.astarworks.astera.match.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * Per-match timer HUD readout (Wave 5match-hud, ADR-0118 §5).
 *
 * # Why a polling scheduler instead of a tick event
 *
 * There is no `MatchTicked` event in the codebase — the match aggregate is
 * a snapshot, time is implicit in `startedAtTick + IScheduler.currentTick`.
 * Emitting a `MatchTick` event 20 times per second per match would flood
 * the bus; pulling the snapshot once per second from the listener is the
 * right place to compute elapsed.
 *
 * Round 10 audit confirmed the pull model matches the existing pattern
 * used by `BarrierStatusScheduler` / `AnomalyScheduler` / `StarGenerator-
 * Scheduler` (all of which poll the match state instead of emitting
 * per-tick events).
 *
 * # Cadence
 *
 * Polls every 20 ticks (1 s). Only matches in [MatchPhase.ACTIVE] or
 * [MatchPhase.SUDDEN_DEATH] render the timer — `WAITING` / `COUNTDOWN` /
 * `ENDED` either have their own dedicated HUD path (countdown is a
 * subtitle countdown in Wave 6b) or no timer at all (ended).
 *
 * # Lifecycle
 *
 * Constructor starts a [IScheduler.TaskHandle]; [stop] cancels it. Match
 * plugin's `onDisable` calls [stop].
 */
public class HudUpdateOnMatchTickListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
    private val scheduler: IScheduler,
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val taskHandle: IScheduler.TaskHandle = scheduler.runRepeatingTicks(
        initialDelayTicks = pollPeriodTicks,
        periodTicks = pollPeriodTicks,
    ) {
        pushForAllActiveMatches()
    }

    /** Cancel the polling task. Idempotent: a cancelled handle is a no-op. */
    public fun stop() {
        taskHandle.cancel()
    }

    private fun pushForAllActiveMatches() {
        val current = scheduler.currentTick()
        for (match in matches.findActiveMatches()) {
            if (match.phase != MatchPhase.ACTIVE && match.phase != MatchPhase.SUDDEN_DEATH) continue
            renderTimerFor(match, currentTick = current)
        }
    }

    private fun renderTimerFor(match: Match, currentTick: Long) {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.match.timer"),
                color = HudColor.WHITE,
            ),
            ttlTicks = ACTIONBAR_TTL_TICKS,
        )
        for (team in match.teams) {
            for (member in team.members) {
                showOrLog(member, layout, match.id.toString())
            }
        }
    }

    private fun showOrLog(playerId: PlayerId, layout: HudLayout, matchKey: String) {
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "Match-timer HUD show failed for {} in match {}: {}",
                playerId,
                matchKey,
                outcome.error,
            )
        }
    }

    public companion object {
        /** 20 ticks = 1 second. Lined up with the wall-clock seconds digit. */
        public const val DEFAULT_POLL_PERIOD_TICKS: Long = 20L

        /** 30 ticks = 1.5 s; a hair longer than the poll period so a single skipped pulse does not blank the action bar. */
        public const val ACTIONBAR_TTL_TICKS: Int = 30
    }
}

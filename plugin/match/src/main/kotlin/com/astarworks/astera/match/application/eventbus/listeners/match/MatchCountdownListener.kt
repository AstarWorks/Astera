package com.astarworks.astera.match.application.eventbus.listeners.match

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * 5-second countdown title broadcast on [MatchStarted] (Wave
 * 5game-complete, ADR-0121 §C).
 *
 * # What it does
 *
 * On the `MatchStarted` event the listener schedules five 1-tick-apart
 * [HudLayout.Subtitle] pushes per participant, counting down "5 → 4 → 3
 * → 2 → 1". Each push uses an i18n key
 * `astera.match.countdown.<n>` so the renderer can swap colour / sound
 * per number (the i18n catalogue lands them as plain strings for now).
 *
 * # Why subtitle, not action-bar
 *
 * Countdown is a one-time, high-priority "look here, now" cue. Subtitle
 * (`Title.title` Adventure surface) is the canonical Minecraft idiom —
 * it dominates the screen, fades cleanly, and the "5..4..3..2..1" cue
 * is what every PvP server uses. Action-bar slot is reserved for
 * persistent stats by `UpdateMatchSidebarListener` + the Wave 5j HUD
 * stack — we don't want to displace those.
 *
 * # Complementary to [HudUpdateOnMatchPhaseChangedListener]
 *
 * That listener pushes "Phase: Active" / "Phase: Sudden Death" on
 * `PhaseChanged`. Countdown is **before** Active starts (between
 * COUNTDOWN and ACTIVE), so the two messages don't collide — by the
 * time PhaseChanged fires, the last "1" has already faded.
 *
 * # Scheduling
 *
 * The listener uses [IScheduler.run] to offset each push by
 * `(5 - n) * 20` ticks. If the listener disables (plugin onDisable)
 * during a countdown, the in-flight `TaskHandle`s are dropped silently
 * — vanilla cancellation flow.
 *
 * # Failure mode
 *
 * Match unknown at `MatchStarted` time → drop (defensive; should be
 * impossible since the use case publishes the event after saving). HUD
 * push failures (player offline) log at DEBUG and continue.
 */
public class MatchCountdownListener(
    private val matches: IMatchRepository,
    private val hudGateway: IHudGateway,
    private val scheduler: IScheduler,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchStarted) {
        val match = matches.findById(event.matchId) ?: return
        val recipients = match.teams.flatMap { it.members }
        if (recipients.isEmpty()) return

        for (n in COUNTDOWN_FROM downTo 1) {
            val offset = (COUNTDOWN_FROM - n).toLong() * TICKS_PER_SECOND
            for (recipient in recipients) scheduler.run(ExecutionScope.Entity(recipient.uuid), ticks(offset)) {
                pushCount(listOf(recipient), n, event.matchId.value)
            }
        }
    }

    private fun pushCount(recipients: List<PlayerId>, n: Int, matchKey: String) {
        val layout = HudLayout.Subtitle(
            component = HudComponent.Text(
                messageKey = MessageKey("astera.match.countdown.$n"),
            ),
            fadeInTicks = SUBTITLE_FADE_IN,
            stayTicks = SUBTITLE_STAY,
            fadeOutTicks = SUBTITLE_FADE_OUT,
        )
        for (recipient in recipients) {
            when (val outcome = hudGateway.showFor(recipient, layout)) {
                is Result.Success -> Unit
                is Result.Failure -> log.debug(
                    "MatchCountdown n={} HUD push failed for {} in match {}: {}",
                    n, recipient, matchKey, outcome.error,
                )
            }
        }
    }

    public companion object {
        /** Countdown start number (5 → 4 → 3 → 2 → 1). */
        public const val COUNTDOWN_FROM: Int = 5

        /** Ticks per second — 20 on Paper / Spigot. */
        public const val TICKS_PER_SECOND: Long = 20L

        /** Subtitle fade-in (≤ 1 tick keeps the cue snappy). */
        public const val SUBTITLE_FADE_IN: Int = 2

        /** Subtitle visible duration (15 ticks ~ 0.75s — covers the gap to next). */
        public const val SUBTITLE_STAY: Int = 15

        /** Subtitle fade-out. */
        public const val SUBTITLE_FADE_OUT: Int = 3
    }
}

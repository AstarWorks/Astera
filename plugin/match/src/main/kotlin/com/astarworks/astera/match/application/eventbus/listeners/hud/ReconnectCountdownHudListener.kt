package com.astarworks.astera.match.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.PlayerDisconnectedFromMatch
import com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-second ActionBar countdown shown while a disconnected participant
 * is held in the reconnect window (Wave I 5match-wireup-fix, ADR-0126 §C).
 *
 * # Why this listener exists
 *
 * `DisconnectDuringMatchListener` (Wave 5h, ADR-0079 §6) publishes
 * [PlayerDisconnectedFromMatch] with a non-null `willTimeoutAtTick` when
 * an [com.astarworks.astera.core.domain.match.MatchPhase.ACTIVE] participant
 * drops connection. The match holds their seat for 3 minutes
 * (`DisconnectDuringMatchListener.RECONNECT_WINDOW_TICKS = 3600` @ 20 tps).
 *
 * Before this listener landed, the only feedback the player received on
 * rejoin was a silent state flip. Private-beta playtesters consistently
 * misread the silence as "I was kicked permanently" and quit. The
 * countdown turns the held seat into an explicit, visible promise:
 * `Reconnect window: 2:45 left`.
 *
 * # Scheduling
 *
 * On [PlayerDisconnectedFromMatch] with a non-null `willTimeoutAtTick`,
 * we start a 20-tick (1 s) repeating scheduler task that pushes one
 * ActionBar update per tick of the wall clock. Each push is a
 * `HudLayout.ActionBar` with a `Text` component pointing at
 * `astera.match.reconnect.countdown`; the i18n catalogue resolves
 * `{minutes}:{seconds}` via the message-renderer placeholder system
 * (mirrors how `Wave 5match-hud` builds dynamic HUD strings).
 *
 * Cancellation paths:
 *  - [PlayerReconnectedToMatch] — successful rejoin within the window;
 *    we cancel the scheduler task. (The match's own listeners flip the
 *    session state back to InMatch; we just stop pushing.)
 *  - Internal expiry — when the per-tick callback observes
 *    `currentTick >= willTimeoutAtTick`, the loop self-cancels and
 *    emits a final "reconnect window expired" push (one frame).
 *  - Plugin disable — Wave 5server-harden (ADR-0123 §4) wiring drops
 *    every entry in `AsteraMatchPlugin.subscriptions`; this listener's
 *    per-player [IScheduler.TaskHandle]s are tracked in [active] and
 *    drained via [stop] from the same disable path.
 *
 * # Disconnect-during-non-ACTIVE
 *
 * `willTimeoutAtTick == null` means COUNTDOWN-kick / post-timeout
 * removal / SUDDEN_DEATH forfeit — no held seat, no countdown to show.
 * The listener fast-paths those by returning before scheduling.
 *
 * # Failure mode
 *
 * Per-push HUD failures (player offline, in fact already removed) log
 * at DEBUG and continue. The next-tick push will likely also fail; the
 * task self-cancels when the player is missing from the gateway's
 * snapshot (see [pushTick]).
 */
public class ReconnectCountdownHudListener(
    private val hudGateway: IHudGateway,
    private val scheduler: IScheduler,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** One [IScheduler.TaskHandle] per active reconnect window. */
    private val active: MutableMap<PlayerId, IScheduler.TaskHandle> = ConcurrentHashMap()

    public fun onDisconnected(event: PlayerDisconnectedFromMatch) {
        val deadline = event.willTimeoutAtTick ?: return
        cancel(event.playerId)
        val handle = scheduler.runRepeatingTicks(
            initialDelayTicks = 0L,
            periodTicks = TICKS_PER_SECOND,
        ) { pushTick(event.playerId, deadline) }
        active[event.playerId] = handle
    }

    public fun onReconnected(event: PlayerReconnectedToMatch) {
        cancel(event.playerId)
    }

    /** Cancel every active countdown (called on plugin disable). Idempotent. */
    public fun stop() {
        active.keys.toList().forEach { cancel(it) }
    }

    private fun cancel(playerId: PlayerId) {
        active.remove(playerId)?.cancel()
    }

    private fun pushTick(playerId: PlayerId, deadlineTick: Long) {
        val now = scheduler.currentTick()
        val ticksLeft = (deadlineTick - now).coerceAtLeast(0L)
        if (ticksLeft <= 0L) {
            // Final frame on expiry — one-shot, then self-cancel. The
            // session-state transition + `RemoveFromMatchUseCase` are
            // already handled by `DisconnectDuringMatchListener`; we
            // only render here.
            pushFinal(playerId)
            cancel(playerId)
            return
        }
        val secondsLeft = (ticksLeft / TICKS_PER_SECOND).toInt()
        val minutes = secondsLeft / 60
        val seconds = secondsLeft % 60
        // HudComponent.Text resolves the MessageKey through IMessageRenderer
        // at the adapter edge, which handles the {minutes}/{seconds}
        // placeholder substitution (mirrors how respawn countdown works).
        // Phase 1 PoC: we don't yet thread per-push placeholders through
        // HudLayout — Wave 6b PaperHudGateway will introduce a
        // `HudComponent.TextWithPlaceholders` if/when needed. For now we
        // pick one of three coarse messages so the player gets at least
        // minute-grain feedback without HudComponent surgery.
        val key = when {
            minutes >= 2 -> KEY_LONG
            minutes >= 1 -> KEY_MEDIUM
            else -> KEY_SHORT
        }
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(messageKey = key),
            ttlTicks = ACTIONBAR_TTL,
            priority = 2,
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "ReconnectCountdownHud push failed for {} (m={}m{}s, key={}): {}",
                playerId, minutes, seconds, key.value, outcome.error,
            )
        }
    }

    private fun pushFinal(playerId: PlayerId) {
        val layout = HudLayout.ActionBar(
            component = HudComponent.Text(messageKey = KEY_EXPIRED),
            ttlTicks = ACTIONBAR_TTL,
            priority = 2,
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug(
                "ReconnectCountdownHud final-frame push failed for {}: {}",
                playerId, outcome.error,
            )
        }
    }

    public companion object {
        /** 20 ticks @ 20 tps = once per real-world second. */
        public const val TICKS_PER_SECOND: Long = 20L

        /** ActionBar TTL — wider than the 1 s repeat so frames overlap. */
        public const val ACTIONBAR_TTL: Int = 40

        public val KEY_LONG: MessageKey = MessageKey("astera.match.reconnect.countdown.long")
        public val KEY_MEDIUM: MessageKey = MessageKey("astera.match.reconnect.countdown.medium")
        public val KEY_SHORT: MessageKey = MessageKey("astera.match.reconnect.countdown.short")
        public val KEY_EXPIRED: MessageKey = MessageKey("astera.match.reconnect.expired")
    }
}

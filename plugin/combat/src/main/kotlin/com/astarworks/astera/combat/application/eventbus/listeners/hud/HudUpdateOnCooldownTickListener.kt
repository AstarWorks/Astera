package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import kotlin.math.ceil

/**
 * The action bar's one persistent owner: the combat readout (WP14, ADR-0177).
 *
 * Shows, left to right, what is on cooldown (count + seconds until the next
 * one is ready) and the active status effects (name, `xN` when stacked). The
 * old status-effect *sidebar* is gone: the sidebar belongs to the match /
 * lobby HUD, and effects are a combat concern that reads fine on one line.
 *
 * # Why a polling scheduler instead of an event
 *
 * [ICooldownTracker] is intentionally event-less (a tick event per cooldown
 * would flood the bus) and no `StatusExpired` fires for every effect timeout,
 * so the readout pulls state on its own cadence.
 *
 * # Lazy redraw
 *
 * Polls every [DEFAULT_POLL_PERIOD_TICKS] ticks but only talks to the gateway
 * when the rendered content changed (seconds are whole numbers, so a running
 * cooldown redraws once a second) or the last send is about to fade
 * ([REFRESH_TICKS]). Priority 0: a toast (currency, level-up, ...) is never
 * overwritten by this readout.
 */
public class HudUpdateOnCooldownTickListener(
    private val cooldownTracker: ICooldownTracker,
    private val sessions: IPlayerSessionRepository,
    private val hudGateway: IHudGateway,
    private val scheduler: IScheduler,
    private val statusEffects: IStatusEffectContainer? = null,
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Sent(val layout: HudLayout.ActionBar, val atTick: Long)

    private val lastSent: MutableMap<PlayerId, Sent> = mutableMapOf()

    private val taskHandle: IScheduler.TaskHandle = scheduler.runRepeatingTicks(
        initialDelayTicks = pollPeriodTicks,
        periodTicks = pollPeriodTicks,
    ) {
        pushForAll()
    }

    /** Cancel the polling task. Idempotent: a cancelled handle is a no-op. */
    public fun stop() {
        taskHandle.cancel()
    }

    private fun pushForAll() {
        val now = scheduler.currentTick()
        val online = sessions.all().map { it.playerId }.toSet()
        lastSent.keys.retainAll(online)
        for (playerId in online) {
            val layout = render(playerId)
            if (layout == null) {
                lastSent.remove(playerId)
                continue
            }
            val previous = lastSent[playerId]
            if (previous != null && previous.layout == layout && now - previous.atTick < REFRESH_TICKS) continue
            lastSent[playerId] = Sent(layout, now)
            when (val outcome = hudGateway.showFor(playerId, layout)) {
                is Result.Success -> Unit
                is Result.Failure -> log.debug("Combat readout show failed for {}: {}", playerId, outcome.error)
            }
        }
    }

    /** `null` when there is nothing to show (no cooldown, no effect). */
    private fun render(playerId: PlayerId): HudLayout.ActionBar? {
        val parts = mutableListOf<HudComponent>()

        val keys = cooldownTracker.activeKeysFor(playerId)
        if (keys.isNotEmpty()) {
            val soonest = keys.minOf { cooldownTracker.remainingTicks(playerId, it) }
            parts += HudComponent.Text(
                messageKey = KEY_COOLDOWN,
                color = HudColor.AQUA,
                placeholders = mapOf(
                    "count" to keys.size.toString(),
                    "seconds" to ceil(soonest / TICKS_PER_SECOND).toLong().coerceAtLeast(1).toString(),
                ),
            )
        }

        for (effect in statusEffects?.active(playerId).orEmpty().take(MAX_EFFECTS)) {
            parts += HudComponent.Text(messageKey = effect.spec.displayNameKey, color = HudColor.WHITE)
            if (effect.currentStacks > 1) {
                parts += HudComponent.Text(
                    messageKey = KEY_STACKS,
                    color = HudColor.GRAY,
                    placeholders = mapOf("stacks" to effect.currentStacks.toString()),
                )
            }
        }

        if (parts.isEmpty()) return null
        return HudLayout.ActionBar(
            component = HudComponent.Composite(parts, spacing = 2),
            ttlTicks = ACTIONBAR_TTL_TICKS,
            priority = 0,
        )
    }

    public companion object {
        /** Half a second: smooth enough for second-granular text, cheap for <= 32 players. */
        public const val DEFAULT_POLL_PERIOD_TICKS: Long = 10L

        /** Resend before the client fades the bar even if nothing changed. */
        public const val REFRESH_TICKS: Long = 20L

        /** Longer than [REFRESH_TICKS] so one skipped pulse does not blank the bar. */
        public const val ACTIONBAR_TTL_TICKS: Int = 30

        public const val MAX_EFFECTS: Int = 4

        private const val TICKS_PER_SECOND: Double = 20.0

        private val KEY_COOLDOWN: MessageKey = MessageKey("astera.hud.cooldown.summary")
        private val KEY_STACKS: MessageKey = MessageKey("astera.hud.status.stacks")
    }
}

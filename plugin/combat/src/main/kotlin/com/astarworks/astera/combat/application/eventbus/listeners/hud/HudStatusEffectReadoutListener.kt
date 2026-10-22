package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IStatusEffectIconSource
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.status.StatusEffectHudGlyphs
import com.astarworks.astera.core.domain.hud.status.StatusEffectHudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * The action bar's one persistent owner: the combat readout (WP14, ADR-0177).
 *
 * Shows the E.G.O gauge (ADR-0231; `E.G.O n/max`, `E.G.O READY` when full) and the
 * active status effects (name, `xN` when stacked) without a loaded pack. With
 * the pack, authored effects move to the anchored icon row; unknown runtime
 * IDs keep their text. This listener also supplies snapshots to Core through
 * [IStatusEffectIconSource], without writing the status-line channel. Cooldowns left this
 * line in ADR-0197: they are per-binding icons on the status line now, so this
 * class (`HudUpdateOnCooldownTickListener` until then) only reads effects.
 *
 * # Why a polling scheduler instead of an event
 *
 * No `StatusExpired` fires for every effect timeout, so the readout pulls state
 * on its own cadence.
 *
 * # Lazy redraw
 *
 * Polls every [DEFAULT_POLL_PERIOD_TICKS] ticks but only talks to the gateway
 * when the rendered content changed or the last send is about to fade
 * ([REFRESH_TICKS]). Priority 0: a toast (currency, level-up, ...) is never
 * overwritten by this readout.
 */
public class HudStatusEffectReadoutListener(
    private val sessions: IPlayerSessionRepository,
    private val hudGateway: IHudGateway,
    private val scheduler: IScheduler,
    private val statusEffects: IStatusEffectContainer,
    pollPeriodTicks: Long = DEFAULT_POLL_PERIOD_TICKS,
    /** ADR-0231: when set, the E.G.O gauge is the first segment of the readout while it holds anything. */
    private val egoGauge: IEgoGauge? = null,
) : IStatusEffectIconSource {
    private val packLoaded: MutableSet<PlayerId> = mutableSetOf()

    public fun onPackStatus(event: ResourcePackStatusChanged) {
        if (event.status.isLoaded) packLoaded += event.playerId else packLoaded -= event.playerId
    }

    public fun onQuit(event: PlayerQuitServer) {
        packLoaded -= event.playerId
        lastSent -= event.playerId
    }

    override fun entriesFor(playerId: PlayerId): List<StatusEffectHudLayout.Entry> =
        statusEffects.active(playerId).map {
            StatusEffectHudLayout.Entry(it.spec.id.value, it.remainingTicks, it.currentStacks)
        }

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

    /** `null` when there is nothing to show (no effect). */
    private fun render(playerId: PlayerId): HudLayout.ActionBar? {
        val parts = mutableListOf<HudComponent>()

        val ego = egoGauge
        val egoValue = ego?.value(playerId) ?: 0
        if (ego != null && egoValue > 0) {
            parts += if (egoValue >= ego.config.max) {
                HudComponent.Text(messageKey = KEY_EGO_READY, color = HudColor.GOLD)
            } else {
                HudComponent.Text(
                    messageKey = KEY_EGO_GAUGE,
                    color = HudColor.LIGHT_PURPLE,
                    placeholders = mapOf("value" to egoValue.toString(), "max" to ego.config.max.toString()),
                )
            }
        }

        val textEffects = statusEffects.active(playerId).filter {
            playerId !in packLoaded || it.spec.id.value !in StatusEffectHudGlyphs.IDS
        }
        for ((index, effect) in textEffects.take(MAX_EFFECTS).withIndex()) {
            if (index > 0 || parts.isNotEmpty()) parts += HudComponent.Text(messageKey = KEY_SEPARATOR)
            // Harmful effects read red, beneficial ones green; stacks and seconds left follow the name.
            parts += HudComponent.Text(
                messageKey = effect.spec.displayNameKey,
                color = if (effect.spec.kind.harmful) HudColor.RED else HudColor.GREEN,
            )
            if (effect.currentStacks > 1) {
                parts += HudComponent.Text(
                    messageKey = KEY_STACKS,
                    color = HudColor.GRAY,
                    placeholders = mapOf("stacks" to effect.currentStacks.toString()),
                )
            }
            parts += HudComponent.Text(
                messageKey = KEY_SECONDS,
                color = HudColor.GRAY,
                placeholders = mapOf("seconds" to secondsLeft(effect.remainingTicks).toString()),
            )
        }

        if (parts.isEmpty()) return null
        return HudLayout.ActionBar(
            component = HudComponent.Composite(parts, spacing = 2),
            ttlTicks = ACTIONBAR_TTL_TICKS,
            priority = 0,
        )
    }

    /** Whole seconds left, rounded up so an effect that is still on never reads 0. */
    private fun secondsLeft(ticks: Int): Int = (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND

    public companion object {
        private const val TICKS_PER_SECOND = 20

        /** Half a second: cheap for <= 32 players, fast enough to follow effect stacks. */
        public const val DEFAULT_POLL_PERIOD_TICKS: Long = 10L

        /** Resend before the client fades the bar even if nothing changed. */
        public const val REFRESH_TICKS: Long = 20L

        /** Longer than [REFRESH_TICKS] so one skipped pulse does not blank the bar. */
        public const val ACTIONBAR_TTL_TICKS: Int = 30

        public const val MAX_EFFECTS: Int = 4

        private val KEY_STACKS: MessageKey = MessageKey("astera.hud.status.stacks")
        private val KEY_SECONDS: MessageKey = MessageKey("astera.hud.status.seconds")
        private val KEY_EGO_GAUGE: MessageKey = MessageKey("astera.hud.ego.gauge")
        private val KEY_EGO_READY: MessageKey = MessageKey("astera.hud.ego.ready")
        private val KEY_SEPARATOR: MessageKey = MessageKey("astera.hud.status.separator")
    }
}

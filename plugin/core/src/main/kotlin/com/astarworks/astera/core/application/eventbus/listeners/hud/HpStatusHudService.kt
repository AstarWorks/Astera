package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.status.HpBarLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The status line's one owner (ADR-0182): keeps every player's HP readout in
 * step with the Astera HP container, which is the source of truth (ADR-0149;
 * the Bukkit hearts are a lossy mirror).
 *
 * # Two renderings
 *
 *  - **Pack loaded** ([ResourcePackStatusChanged] `LOADED`): the glyph bar from
 *    [HpBarLayout]; the pack has already blanked the vanilla hearts.
 *  - **Otherwise** (no pack configured, declined, still downloading): the text
 *    `HP cur/max`, and the vanilla hearts keep mirroring.
 *
 * # Throttling
 *
 * A task every [FLUSH_PERIOD_TICKS] ticks re-renders every joined player and
 * sends the ones whose rendered line actually changed, so a burst of damage
 * ticks costs one update, and an unchanged line costs nothing. There is no
 * per-event trigger on purpose: the poll also catches writers that touch the
 * container without raising `PlayerHealthChanged` (the combat damage gateway
 * does exactly that), so one path covers every writer.
 *
 * # Unset HP
 *
 * Nothing seeds the HP container on join, so a fresh player has neither
 * current nor max HP. That is "full health at [DEFAULT_MAX_HP]" (the same
 * default the health and damage gateways use), not "nothing to show": a
 * missing value used to leave the line unsent forever.
 */
public class HpStatusHudService(
    private val health: IPlayerHealthGateway,
    private val hudGateway: IHudGateway,
    scheduler: IScheduler,
    flushPeriodTicks: Long = FLUSH_PERIOD_TICKS,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val packLoaded: MutableSet<PlayerId> = mutableSetOf()
    private val joined: MutableSet<PlayerId> = linkedSetOf()
    private val failureLogged: MutableSet<PlayerId> = mutableSetOf()
    private val lastSent: MutableMap<PlayerId, HudLayout.StatusLine> = mutableMapOf()

    private val task: IScheduler.TaskHandle = scheduler.runRepeatingTicks(flushPeriodTicks, flushPeriodTicks) { flush() }

    public fun stop() {
        task.cancel()
    }

    public fun onJoined(event: PlayerJoinedServer) {
        joined += event.playerId
    }

    public fun onQuit(event: PlayerQuitServer) {
        forget(event.playerId)
    }

    public fun onPackStatus(event: ResourcePackStatusChanged) {
        if (event.status.isLoaded) packLoaded.add(event.playerId) else packLoaded.remove(event.playerId)
    }

    /** Sends every pending update now (also called by the task). */
    public fun flush() {
        if (joined.isEmpty()) return
        for (playerId in joined.toList()) {
            val layout = render(playerId)
            if (lastSent[playerId] == layout) continue
            when (val outcome = hudGateway.showFor(playerId, layout)) {
                is Result.Success -> lastSent[playerId] = layout
                is Result.Failure -> {
                    if (outcome.error is HudError.UnknownPlayer) {
                        forget(playerId)
                    } else if (failureLogged.add(playerId)) {
                        log.warn("HP status HUD show failed for {}: {}", playerId, outcome.error)
                    }
                }
            }
        }
    }

    private fun forget(playerId: PlayerId) {
        packLoaded -= playerId
        joined -= playerId
        failureLogged -= playerId
        lastSent -= playerId
    }

    private fun render(playerId: PlayerId): HudLayout.StatusLine {
        val max = health.getMaxHealth(playerId) ?: DEFAULT_MAX_HP
        val hp = health.getCurrentHealth(playerId) ?: max
        if (playerId in packLoaded) {
            return HudLayout.StatusLine(HpBarLayout.render(hp, max), anchorLeftPx = HpBarLayout.HEARTS_LEFT_PX)
        }
        val maxInt = max.roundToInt().coerceAtLeast(1)
        return HudLayout.StatusLine(
            HudComponent.Text(
                messageKey = KEY_HP,
                color = HudColor.WHITE,
                placeholders = mapOf(
                    "current" to ceil(hp).toInt().coerceIn(0, maxInt).toString(),
                    "max" to maxInt.toString(),
                ),
            ),
        )
    }

    public companion object {
        /** 0.1 s: faster than a player can read a number, slower than a damage burst. */
        public const val FLUSH_PERIOD_TICKS: Long = 2L

        /** Max HP of a player whose container holds none; matches the health / damage gateways. */
        public const val DEFAULT_MAX_HP: Double = 20.0

        private val KEY_HP: MessageKey = MessageKey("astera.hud.status.hp")
    }
}

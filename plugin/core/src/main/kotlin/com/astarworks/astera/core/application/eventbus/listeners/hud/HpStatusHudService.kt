package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerMaxHealthChanged
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.PlayerRespawned
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
 * Events only mark a player dirty. A task every [FLUSH_PERIOD_TICKS] ticks
 * sends the dirty players whose rendered line actually changed, so a burst of
 * damage ticks costs one update, and an unchanged line costs nothing.
 */
public class HpStatusHudService(
    private val health: IPlayerHealthGateway,
    private val hudGateway: IHudGateway,
    scheduler: IScheduler,
    flushPeriodTicks: Long = FLUSH_PERIOD_TICKS,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val packLoaded: MutableSet<PlayerId> = mutableSetOf()
    private val dirty: MutableSet<PlayerId> = linkedSetOf()
    private val lastSent: MutableMap<PlayerId, HudLayout.StatusLine> = mutableMapOf()

    private val task: IScheduler.TaskHandle = scheduler.runRepeatingTicks(flushPeriodTicks, flushPeriodTicks) { flush() }

    public fun stop() {
        task.cancel()
    }

    public fun onJoined(event: PlayerJoinedServer) {
        dirty += event.playerId
    }

    public fun onHealthChanged(event: PlayerHealthChanged) {
        dirty += event.playerId
    }

    public fun onMaxHealthChanged(event: PlayerMaxHealthChanged) {
        dirty += event.playerId
    }

    public fun onRespawned(event: PlayerRespawned) {
        dirty += event.playerId
    }

    public fun onQuit(event: PlayerQuitServer) {
        forget(event.playerId)
    }

    public fun onPackStatus(event: ResourcePackStatusChanged) {
        val changed = if (event.status.isLoaded) packLoaded.add(event.playerId) else packLoaded.remove(event.playerId)
        if (changed) dirty += event.playerId
    }

    /** Sends every pending update now (also called by the task). */
    public fun flush() {
        if (dirty.isEmpty()) return
        for (playerId in dirty.toList()) {
            val layout = render(playerId) ?: continue // no HP yet: stay dirty and retry
            dirty -= playerId
            if (lastSent[playerId] == layout) continue
            when (val outcome = hudGateway.showFor(playerId, layout)) {
                is Result.Success -> lastSent[playerId] = layout
                is Result.Failure -> {
                    if (outcome.error is HudError.UnknownPlayer) forget(playerId)
                    log.debug("HP status HUD show failed for {}: {}", playerId, outcome.error)
                }
            }
        }
    }

    private fun forget(playerId: PlayerId) {
        packLoaded -= playerId
        dirty -= playerId
        lastSent -= playerId
    }

    private fun render(playerId: PlayerId): HudLayout.StatusLine? {
        val hp = health.getCurrentHealth(playerId) ?: return null
        val max = health.getMaxHealth(playerId) ?: return null
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

        private val KEY_HP: MessageKey = MessageKey("astera.hud.status.hp")
    }
}

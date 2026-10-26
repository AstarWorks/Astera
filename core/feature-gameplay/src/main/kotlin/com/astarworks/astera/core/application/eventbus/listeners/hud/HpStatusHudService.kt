package com.astarworks.astera.core.application.eventbus.listeners.hud

import java.util.concurrent.ConcurrentHashMap

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.ICooldownIconSource
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IStatusEffectIconSource
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerMaxHealthChanged
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.status.AmmoReadoutLayout
import com.astarworks.astera.core.domain.hud.status.CooldownHudLayout
import com.astarworks.astera.core.domain.hud.status.HpBarLayout
import com.astarworks.astera.core.domain.hud.status.StatusEffectHudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.slf4j.LoggerFactory

/**
 * The status line's one owner (ADR-0182): keeps every player's HP readout in
 * step with the Astera HP container, which is the source of truth (ADR-0149;
 * the Bukkit hearts are a lossy mirror).
 *
 * # Ammo (F2-2, ADR-0194)
 *
 * While the player holds a gun (an item whose resource is [ResourceType.Ammo])
 * the same line also carries `[bullet] cur/max` right of the HP bar. That
 * replaces the boss-bar weapon-resource HUD of ADR-0177; energy is on the food bar.
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
 * A health event ([onHealthChanged] / [onMaxHealthChanged]) pushes that player's
 * line at once, so a hit shows on the tick it lands. A task every
 * [FLUSH_PERIOD_TICKS] ticks still re-renders every joined player as the
 * backstop (ammo, cooldown and status icons have no health event). Either way
 * only a line that actually changed is sent, so an unchanged line costs nothing.
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
    private val pools: IResourcePoolService,
    private val items: IItemRegistry,
    private val scheduler: IScheduler,
    flushPeriodTicks: Long = FLUSH_PERIOD_TICKS,
    private val cooldownIcons: () -> ICooldownIconSource? = { null },
    private val statusEffectIcons: () -> IStatusEffectIconSource? = { null },
    /**
     * Everyone online right now. A plugin reload / hotswap drops [joined] while the clients stay connected and no
     * `PlayerJoinedServer` follows, so without this their status line (and, with the pack applied, the only HP
     * readout there is) never came back until they re-logged.
     */
    private val onlinePlayers: () -> Collection<PlayerId> = { emptyList() },
    /** UI-03: a player who switched the HP line off in Settings gets no status line at all. */
    private val lineVisible: (PlayerId) -> Boolean = { true },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val packLoaded: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val joined: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val failureLogged: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val lastSent: MutableMap<PlayerId, HudLayout.StatusLine> = ConcurrentHashMap()
    private val hidden: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()

    private val task: IScheduler.TaskHandle = scheduler.runRepeatingTicks(ExecutionScope.Global, flushPeriodTicks, flushPeriodTicks) { flush() }

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

    /** HP changed through any writer: show it now rather than at the next flush. */
    public fun onHealthChanged(event: PlayerHealthChanged) {
        pushIfJoined(event.playerId)
    }

    /** Max HP changed: the bar's scale moved with it. */
    public fun onMaxHealthChanged(event: PlayerMaxHealthChanged) {
        pushIfJoined(event.playerId)
    }

    private fun pushIfJoined(playerId: PlayerId) {
        if (playerId in joined) push(playerId)
    }

    /** Sends every pending update now (also called by the task). */
    public fun flush() {
        val targets = LinkedHashSet<PlayerId>(joined)
        targets += onlinePlayers()
        for (playerId in targets) push(playerId)
    }

    private fun push(playerId: PlayerId) {
        scheduler.run(ExecutionScope.Entity(playerId.uuid), kotlin.time.Duration.ZERO) {
            if (!lineVisible(playerId)) {
                if (hidden.add(playerId)) {
                    lastSent -= playerId
                    hudGateway.clearFor(playerId, HudRegion.STATUS_LINE)
                }
                return@run
            }
            hidden -= playerId
            val layout = render(playerId)
            if (lastSent[playerId] == layout) return@run
            when (val outcome = hudGateway.showFor(playerId, layout)) {
                is Result.Success -> lastSent[playerId] = layout
                is Result.Failure -> {
                    if (outcome.error is HudError.UnknownPlayer) {
                        // Gone (or not resolvable yet): drop the cached line only. The pack status survives, and
                        // an online player is picked up again by the next flush.
                        joined -= playerId
                        lastSent -= playerId
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
        hidden -= playerId
    }

    /** `current to max` ammo of the gun in hand, or `null` when the held item uses no ammo. */
    private fun heldAmmo(playerId: PlayerId): Pair<Int, Int>? {
        val pool = pools.currentPool(playerId) ?: return null
        val weapon = pool.activeWeapon ?: return null
        if (items.find(weapon)?.resourceType != ResourceType.Ammo) return null
        return pool.ammo to pools.capFor(playerId, ResourceType.Ammo).maxPool
    }

    private fun render(playerId: PlayerId): HudLayout.StatusLine {
        val max = health.getMaxHealth(playerId) ?: DEFAULT_MAX_HP
        val hp = health.getCurrentHealth(playerId) ?: max
        val ammo = heldAmmo(playerId)
        // ADR-0197: one icon per bound input of the held item, over the HP bar / after the HP text.
        val cooldowns = cooldownIcons()?.entriesFor(playerId).orEmpty()
        if (playerId in packLoaded) {
            val bar = HpBarLayout.render(hp, max)
            val block = if (ammo == null) bar else AmmoReadoutLayout.appendTo(bar, AmmoReadoutLayout.render(ammo.first, ammo.second))
            val withCooldowns = CooldownHudLayout.overlay(block, cooldowns)
            val withEffects = StatusEffectHudLayout.overlay(withCooldowns, statusEffectIcons()?.entriesFor(playerId).orEmpty())
            return HudLayout.StatusLine(withEffects, anchorLeftPx = HpBarLayout.HEARTS_LEFT_PX)
        }
        val maxInt = max.roundToInt().coerceAtLeast(1)
        val placeholders = mutableMapOf(
            "current" to ceil(hp).toInt().coerceIn(0, maxInt).toString(),
            "max" to maxInt.toString(),
        )
        if (ammo != null) {
            placeholders["ammo"] = ammo.first.toString()
            placeholders["ammoMax"] = ammo.second.toString()
        }
        val hpText = HudComponent.Text(
            messageKey = if (ammo == null) KEY_HP else KEY_HP_AMMO,
            color = HudColor.WHITE,
            placeholders = placeholders,
        )
        val cooldownText = CooldownHudLayout.textParts(cooldowns)
        return HudLayout.StatusLine(
            if (cooldownText.isEmpty()) hpText else HudComponent.Composite(listOf(hpText) + cooldownText, spacing = 2),
        )
    }

    public companion object {
        /** 0.1 s: faster than a player can read a number, slower than a damage burst. */
        public const val FLUSH_PERIOD_TICKS: Long = 2L

        /** Max HP of a player whose container holds none; matches the health / damage gateways. */
        public const val DEFAULT_MAX_HP: Double = 20.0

        private val KEY_HP: MessageKey = MessageKey("astera.hud.status.hp")
        private val KEY_HP_AMMO: MessageKey = MessageKey("astera.hud.status.hp-ammo")
    }
}

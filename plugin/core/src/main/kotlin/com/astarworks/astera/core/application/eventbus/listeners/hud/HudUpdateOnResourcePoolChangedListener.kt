package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.core.domain.event.ResourceReloadStarted
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.hud.BossBarColor
import com.astarworks.astera.core.domain.hud.BossBarStyle
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.resource.key
import org.slf4j.LoggerFactory

/**
 * Weapon-resource HUD (WP2; WP14 / ADR-0177).
 *
 * **Owns boss-bar slot 1 and nothing else.** Shows the resource of the
 * **held** item only (ammo for a gun, mana for a staff, stamina for a blade)
 * as a boss bar with `remaining / max` text and a proportional fill. Switching
 * to an item without a resource clears the bar. Boss bar rather than action
 * bar because the cooldown readout owns the action bar.
 *
 * At-a-glance states (same slot, different colour / label):
 *  - normal: type colour, `remaining/max`,
 *  - empty ammo: red, "reload" prompt,
 *  - reloading: yellow, the bar fills with the reload timer ([ResourceReloadStarted],
 *    redrawn every [RELOAD_REDRAW_TICKS]),
 *  - just reloaded: green flash until the next change.
 *
 * Triggers: [ResourceSpent], [ResourceReloaded], [ResourceRegenned] (throttled
 * to one redraw per [REGEN_REDRAW_TICKS]) and [PlayerActiveWeaponChanged].
 * Redraws whose content is identical to the last one are dropped, so a full
 * pool regenerating does not touch the client.
 */
public class HudUpdateOnResourcePoolChangedListener(
    private val hudGateway: IHudGateway,
    private val pools: IResourcePoolService,
    private val items: IItemRegistry,
    private val scheduler: IScheduler,
) {

    private data class Reload(val weapon: ItemId, val startTick: Long, val durationTicks: Long)

    private val reloading: MutableMap<PlayerId, Reload> = mutableMapOf()

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastRegenDraw: MutableMap<Pair<PlayerId, ResourceType>, Long> = mutableMapOf()
    private val lastDrawn: MutableMap<PlayerId, HudLayout.BossBarSlot> = mutableMapOf()
    private val justReloaded: MutableSet<PlayerId> = mutableSetOf()

    public fun onSpent(event: ResourceSpent) {
        justReloaded.remove(event.playerId)
        draw(event.playerId, event.type)
    }

    public fun onReloadStarted(event: ResourceReloadStarted) {
        justReloaded.remove(event.playerId)
        reloading[event.playerId] = Reload(event.weapon, event.atTick, event.durationTicks)
        draw(event.playerId, ResourceType.Ammo)
        scheduler.runLater(ticks(RELOAD_REDRAW_TICKS)) { redrawReload(event.playerId, event.atTick) }
    }

    public fun onReloaded(event: ResourceReloaded) {
        reloading.remove(event.playerId)
        justReloaded += event.playerId
        draw(event.playerId, ResourceType.Ammo)
    }

    public fun onRegenned(event: ResourceRegenned) {
        val key = event.playerId to event.type
        val last = lastRegenDraw[key]
        if (last != null && event.atTick - last < REGEN_REDRAW_TICKS) return
        lastRegenDraw[key] = event.atTick
        draw(event.playerId, event.type)
    }

    public fun onActiveWeaponChanged(event: PlayerActiveWeaponChanged) {
        justReloaded.remove(event.playerId)
        // Switching away does not cancel the reload timer, but the bar must follow the new item.
        if (reloading[event.playerId]?.weapon != event.current) reloading.remove(event.playerId)
        val heldType = event.current?.let { items.find(it)?.resourceType }
        if (heldType == null) {
            lastDrawn.remove(event.playerId)
            hudGateway.clearFor(event.playerId, HudRegion.BOSS_BAR_SLOT_1)
            return
        }
        draw(event.playerId, heldType)
    }

    private fun redrawReload(playerId: PlayerId, startedAt: Long) {
        val reload = reloading[playerId] ?: return
        if (reload.startTick != startedAt) return // a newer reload owns the animation
        draw(playerId, ResourceType.Ammo)
        if (scheduler.currentTick() - reload.startTick < reload.durationTicks) {
            scheduler.runLater(ticks(RELOAD_REDRAW_TICKS)) { redrawReload(playerId, startedAt) }
        }
    }

    private fun draw(playerId: PlayerId, type: ResourceType) {
        val pool = pools.currentPool(playerId) ?: return
        val heldType = pool.activeWeapon?.let { items.find(it)?.resourceType }
        if (heldType != type) return
        val max = pools.capFor(playerId, type).maxPool
        val value = pool.valueOf(type)
        val reload = if (type == ResourceType.Ammo) reloading[playerId] else null
        val reloadFraction = reload?.let {
            ((scheduler.currentTick() - it.startTick).toFloat() / it.durationTicks).coerceIn(0f, 1f)
        }
        val flash = type == ResourceType.Ammo && playerId in justReloaded
        val empty = type == ResourceType.Ammo && value <= 0
        val labelKey = when {
            reload != null -> "astera.hud.resource.ammo.reloading"
            empty -> "astera.hud.resource.ammo.empty"
            flash -> "astera.hud.resource.ammo.reloaded"
            else -> "astera.hud.resource.${type.key}.label"
        }
        val layout = HudLayout.BossBarSlot(
            slot = SLOT,
            component = HudComponent.Text(
                messageKey = MessageKey(labelKey),
                color = colorFor(type),
                placeholders = mapOf("remaining" to value.toString(), "max" to max.toString()),
            ),
            color = when {
                reload != null -> BossBarColor.YELLOW
                empty -> BossBarColor.RED
                flash -> BossBarColor.GREEN
                else -> bossColorFor(type)
            },
            style = BossBarStyle.PROGRESS,
            progress = reloadFraction ?: if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f),
        )
        if (lastDrawn[playerId] == layout) return
        lastDrawn[playerId] = layout
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug("Resource HUD show failed for {} ({}): {}", playerId, type, outcome.error)
        }
    }

    private fun colorFor(type: ResourceType): HudColor = when (type) {
        ResourceType.Mana -> HudColor.AQUA
        ResourceType.Stamina -> HudColor.YELLOW
        ResourceType.Ammo -> HudColor.WHITE
    }

    private fun bossColorFor(type: ResourceType): BossBarColor = when (type) {
        ResourceType.Mana -> BossBarColor.BLUE
        ResourceType.Stamina -> BossBarColor.YELLOW
        ResourceType.Ammo -> BossBarColor.WHITE
    }

    public companion object {
        public const val REGEN_REDRAW_TICKS: Long = 5L

        /** A reload bar moves in quarter-second steps. */
        public const val RELOAD_REDRAW_TICKS: Long = 5L

        /** The weapon resource is the only thing the HUD ever puts in boss-bar slot 1. */
        public const val SLOT: Int = 1
    }
}

package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.hud.BossBarColor
import com.astarworks.astera.core.domain.hud.BossBarStyle
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.resource.key
import org.slf4j.LoggerFactory

/**
 * Weapon-resource HUD (WP2; originally Wave 5match-hud, ADR-0118 §2).
 *
 * Shows the resource of the **held** item only (ammo for a gun, mana for a
 * staff, stamina for a blade) as a boss bar with `remaining / max` text and a
 * proportional fill. Switching weapons clears the other types' bars. Boss bar
 * rather than action bar because the cooldown readout already owns the action
 * bar and would overwrite it every 5 ticks.
 *
 * Triggers: [ResourceSpent], [ResourceReloaded], [ResourceRegenned] (throttled
 * to one redraw per [REGEN_REDRAW_TICKS]) and [PlayerActiveWeaponChanged].
 * Max / current are read from [IResourcePoolService] at draw time.
 */
public class HudUpdateOnResourcePoolChangedListener(
    private val hudGateway: IHudGateway,
    private val pools: IResourcePoolService,
    private val items: IItemRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val lastRegenDraw: MutableMap<Pair<PlayerId, ResourceType>, Long> = mutableMapOf()

    public fun onSpent(event: ResourceSpent) {
        draw(event.playerId, event.type)
    }

    public fun onReloaded(event: ResourceReloaded) {
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
        val heldType = event.current?.let { items.find(it)?.resourceType }
        for (type in ResourceType.all) {
            if (type != heldType) hudGateway.clearFor(event.playerId, regionFor(type))
        }
        if (heldType != null) draw(event.playerId, heldType)
    }

    private fun draw(playerId: PlayerId, type: ResourceType) {
        val pool = pools.currentPool(playerId) ?: return
        val heldType = pool.activeWeapon?.let { items.find(it)?.resourceType }
        if (heldType != type) return
        val max = pools.capFor(playerId, type).maxPool
        val value = pool.valueOf(type)
        val layout = HudLayout.BossBarSlot(
            slot = slotFor(type),
            component = HudComponent.Text(
                messageKey = MessageKey("astera.hud.resource.${type.key}.label"),
                color = colorFor(type),
                placeholders = mapOf("remaining" to value.toString(), "max" to max.toString()),
            ),
            color = bossColorFor(type),
            style = BossBarStyle.PROGRESS,
            progress = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f),
        )
        when (val outcome = hudGateway.showFor(playerId, layout)) {
            is Result.Success -> Unit
            is Result.Failure -> log.debug("Resource HUD show failed for {} ({}): {}", playerId, type, outcome.error)
        }
    }

    private fun slotFor(type: ResourceType): Int = when (type) {
        ResourceType.Mana -> 1
        ResourceType.Stamina -> 2
        ResourceType.Ammo -> 3
    }

    private fun regionFor(type: ResourceType): HudRegion = when (slotFor(type)) {
        1 -> HudRegion.BOSS_BAR_SLOT_1
        2 -> HudRegion.BOSS_BAR_SLOT_2
        else -> HudRegion.BOSS_BAR_SLOT_3
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
    }
}

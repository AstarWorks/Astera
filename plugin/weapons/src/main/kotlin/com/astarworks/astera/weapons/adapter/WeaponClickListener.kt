package com.astarworks.astera.weapons.adapter

import com.astarworks.astera.core.adapter.api.event.IMcEvent
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.weapons.application.port.outbound.IWeaponRegistry
import com.astarworks.astera.weapons.application.usecase.FireWeaponUseCase
import com.astarworks.astera.weapons.domain.weapon.WeaponSpec

/**
 * Pure-Kotlin click-to-fire sink. Receives [IMcEvent] from Core's
 * `BukkitEventAdapter`, filters to left-click events, identifies the held
 * weapon by matching its registered item-template material against the
 * player's main-hand material, gates on [ICooldownTracker], and dispatches
 * the [FireWeaponUseCase].
 *
 * ADR-0022 Phase 3: lifted out of the legacy `AsteraPlugin.handleFire`
 * inline lambda. The framework conversion (Bukkit → `IMcEvent`) stays in
 * Core; the **what to do with each event** decision lives here, in the
 * Weapons plugin where the weapon-specific logic belongs.
 *
 * Stage 1 interim: weapon-in-hand detection compares vanilla material
 * names. Stage 2 H replaces this with a `PersistentDataContainer` tag
 * lookup (the `astera:item-id` tag PaperPlayer writes at give-time), so
 * multiple weapons can share the same vanilla material.
 */
public class WeaponClickListener(
    private val fireWeapon: FireWeaponUseCase,
    private val cooldowns: ICooldownTracker,
    private val weapons: IWeaponRegistry,
    private val itemRegistry: IItemRegistry<IMcItemTemplate>,
    private val messages: IMessageRenderer,
) {

    /** Single entrypoint passed to `BukkitEventAdapter { event -> handle(event) }`. */
    public fun handle(event: IMcEvent) {
        when (event) {
            is IMcEvent.PlayerLeftClickAir, is IMcEvent.PlayerLeftClickBlock -> handleFire(event)
            // Wave 5a added PlayerJoined / PlayerQuit / BlockPlaced / BlockBroken
            // for downstream waves (5d/5e); WeaponClickListener doesn't react.
            is IMcEvent.PlayerJoined,
            is IMcEvent.PlayerQuit,
            is IMcEvent.BlockPlaced,
            is IMcEvent.BlockBroken,
            -> Unit
        }
    }

    private fun handleFire(event: IMcEvent) {
        val player = event.player
        val held = player.heldItemMaterialKey() ?: return
        val weapon: WeaponSpec = weapons.all().firstOrNull { spec ->
            val template = itemRegistry.find(spec.itemId)
            template is IMcItemTemplate.Vanilla && template.material == held
        } ?: return

        val cooldownKey = CooldownKey.Weapon(weapon.id.value)
        if (cooldowns.isCoolingDown(player.id, cooldownKey)) {
            val remainingTicks = cooldowns.remainingTicks(player.id, cooldownKey)
            val remainingSeconds = "%.1fs".format(remainingTicks / 20.0)
            player.sendMessage(
                messages.render(
                    player.id,
                    MessageKey("astera.weapon.on_cooldown"),
                    mapOf("weapon" to weapon.id.value, "remaining" to remainingSeconds),
                ),
            )
            return
        }

        cooldowns.start(player.id, cooldownKey, weapon.cooldownTicks.toLong())
        fireWeapon.execute(playerId = player.id, weaponId = weapon.id, at = player.location)
    }
}

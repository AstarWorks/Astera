package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.plugin.java.JavaPlugin

/**
 * WP3 / ADR-0149 §D — stops the vanilla copy of a melee hit from an Astera
 * item so it never double-applies next to the melee skill.
 *
 * An Astera weapon's damage is defined by its bound skill (e.g. `sword-slash`),
 * resolved through `ApplyDamageUseCase`. The same swing also makes Bukkit raise
 * `EntityDamageByEntityEvent(ENTITY_ATTACK / ENTITY_SWEEP_ATTACK)` with the
 * material's vanilla damage; left alone it would hurt a mob directly, or reach
 * core `BukkitEventAdapter` Role 2 which re-routes it into the player HP
 * container as a second hit. This listener cancels it at LOWEST (before that
 * adapter, which is `ignoreCancelled`).
 *
 * "Astera item" = the held stack carries any PDC key named `item_id` (the items
 * plugin stamps `<plugin>:item_id`; matching on the key name keeps combat
 * independent of the items plugin's namespace). Fists and vanilla tools are
 * untouched. Damage this plugin itself applies to mobs is let through via
 * [PaperEntityDamageGateway.applying].
 */
public class VanillaMeleeSuppressor(
    private val entityDamageGateway: PaperEntityDamageGateway?,
) : Listener {

    public fun start(plugin: JavaPlugin, listeners: OwnedListeners): VanillaMeleeSuppressor {
        listeners.register(this)
        return this
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onMeleeDamage(event: EntityDamageByEntityEvent) {
        if (event.cause != DamageCause.ENTITY_ATTACK && event.cause != DamageCause.ENTITY_SWEEP_ATTACK) return
        val attacker = event.damager as? Player ?: return
        if (entityDamageGateway?.applying == true) return
        if (isAsteraItem(attacker)) event.isCancelled = true
    }

    private fun isAsteraItem(player: Player): Boolean {
        val meta = player.inventory.itemInMainHand.itemMeta ?: return false
        return meta.persistentDataContainer.keys.any { it.key == ITEM_ID_KEY }
    }

    public companion object {
        /** Mirrors `PaperItemIdResolver.ITEM_ID_KEY` in the items plugin. */
        public const val ITEM_ID_KEY: String = "item_id"
    }
}

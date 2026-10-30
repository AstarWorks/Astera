package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.combat.application.service.SummonRegistry
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.entity.Entity
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Runs at LOWEST so it precedes Core's damage re-routing (LOW), which would
 * otherwise consume the event first.
 *
 * Keeps summoned mobs (WP5) off their owner's side: a summon neither picks its
 * owner, the owner's teammates or the owner's other summons as an attack target,
 * nor damages them (a stray hit, a splash). Decisions come from
 * [SummonRegistry.isFriendly]; everything else is left to vanilla AI.
 */
public class SummonAllegianceListener(
    private val registry: SummonRegistry,
) : Listener {

    public fun start(plugin: JavaPlugin, listeners: OwnedListeners) {
        listeners.register(this)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onTarget(event: EntityTargetEvent) {
        val target = event.target ?: return
        if (registry.isFriendly(id(event.entity), id(target))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager.let { if (it is Projectile) (it.shooter as? Entity) ?: it else it }
        if (registry.isFriendly(id(damager), id(event.entity))) event.isCancelled = true
    }

    private fun id(entity: Entity): PlayerId = PlayerId(entity.uniqueId)
}

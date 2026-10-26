package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.combat.application.service.PendingVanillaAttribution
import com.astarworks.astera.combat.application.service.VanillaKillResolver
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Publishes `PlayerKilled` for deaths that do not go through
 * `ApplyDamageUseCase` (vanilla melee, mob arrows, fall / lava / void, DoT).
 *
 * Core's `BukkitEventAdapter` re-routes that damage into
 * `IPlayerHealthGateway`, which raises `PlayerHealthChanged`; this class
 * turns the `>0 -> <=0` edge into one `PlayerKilled` via
 * [VanillaKillResolver]. Combat damage (`PaperDamageGateway`) raises
 * `PlayerHealthChanged` too (for the HUDs) but with a non-damage source the
 * resolver ignores, so there is no double publish.
 *
 * Attribution: a LOWEST `EntityDamageEvent` handler (runs before core's LOW
 * re-route, synchronously in the same dispatch) records the attacking player
 * / projectile shooter; the health-changed subscriber consumes it.
 */
public class PaperVanillaKillPublisher(
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val resolver: VanillaKillResolver,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val pending: MutableMap<PlayerId, PendingVanillaAttribution> = ConcurrentHashMap()

    /** Registers the Bukkit listener and the bus subscription; caller keeps the subscription for onDisable. */
    public fun start(plugin: JavaPlugin): IDomainEventBus.Subscription {
        plugin.server.pluginManager.registerEvents(this, plugin)
        return eventBus.subscribe(PlayerHealthChanged::class, EventDelivery.Entity) { onHealthChanged(it) }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onEntityDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val victimId = PlayerId(victim.uniqueId)
        val killer: Player? = (event as? EntityDamageByEntityEvent)?.damager?.let { damager ->
            when (damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> null
            }
        }
        pending[victimId] = PendingVanillaAttribution(
            victim = victimId,
            killer = killer?.uniqueId?.let { PlayerId(it) },
            atTick = scheduler.currentTick(),
        )
    }

    private fun onHealthChanged(event: PlayerHealthChanged) {
        val killed = resolver.resolve(event, pending.remove(event.playerId)) ?: return
        try {
            eventBus.publish(killed)
        } catch (ex: RuntimeException) {
            log.warn("PaperVanillaKillPublisher: PlayerKilled publish failed for {}: {}", event.playerId, ex.message)
        }
    }
}

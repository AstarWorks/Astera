package com.astarworks.astera.core.adapter.paper.entity

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.application.port.outbound.entity.IManagedEntityRegistry
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.entity.Entity
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps helper entities from multiplying across restarts. On [start] and on [stop] it purges every
 * entity tagged `astera_managed`; whenever a world / chunk's entities load it removes managed
 * entities nobody in this process owns (stale from a crash, `/reload` or a saved chunk) and
 * **legacy** ones: untagged text displays saved into the chunk by builds before the entities were
 * marked non-persistent. Legacy entities are identified by position — a feature that knows where
 * its hologram stands registers a [LegacyZone] through [addLegacyZone].
 */
public class PaperManagedEntityJanitor(
    private val plugin: JavaPlugin,
    private val listeners: OwnedListeners,
    private val registry: IManagedEntityRegistry,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val zones = CopyOnWriteArrayList<LegacyZone>()

    /** Untagged [TextDisplay]s within [radius] blocks of [center] in [world] are legacy duplicates. */
    public data class LegacyZone(val world: WorldId, val center: Vec3, val radius: Double)

    public fun start() {
        listeners.register(this)
        val removed = PaperManagedEntities.purge(plugin.server)
        if (removed > 0) log.info("Purged {} stale Astera-managed entities on boot", removed)
    }

    public fun stop() {
        PaperManagedEntities.purge(plugin.server)
    }

    /** Registers [zone] and sweeps it right away in the already loaded chunks. */
    public fun addLegacyZone(zone: LegacyZone) {
        if (zone !in zones) zones += zone
        val world = plugin.server.getWorld(zone.world.value) ?: return
        val removed = sweepZone(world.entities, zone)
        if (removed > 0) log.info("Removed {} legacy hologram duplicates near {} in {}", removed, zone.center, zone.world.value)
    }

    @EventHandler
    public fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) removeIfStale(entity)
        zones.filter { it.world.value == event.world.name }.forEach { sweepZone(event.entities, it) }
    }

    @EventHandler
    public fun onWorldLoad(event: WorldLoadEvent) {
        for (entity in event.world.entities) removeIfStale(entity)
    }

    private fun removeIfStale(entity: Entity) {
        if (PaperManagedEntities.isManaged(entity) && !registry.isClaimed(entity.uniqueId)) entity.remove()
    }

    private fun sweepZone(entities: Collection<Entity>, zone: LegacyZone): Int {
        var removed = 0
        for (entity in entities) {
            if (entity !is TextDisplay || PaperManagedEntities.isManaged(entity) || !entity.isValid) continue
            val l = entity.location
            val dx = l.x - zone.center.x
            val dy = l.y - zone.center.y
            val dz = l.z - zone.center.z
            if (dx * dx + dy * dy + dz * dz <= zone.radius * zone.radius) {
                entity.remove()
                removed++
            }
        }
        return removed
    }
}

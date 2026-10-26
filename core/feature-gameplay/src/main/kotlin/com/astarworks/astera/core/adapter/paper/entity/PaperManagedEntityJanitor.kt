package com.astarworks.astera.core.adapter.paper.entity

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
    private val registry: IManagedEntityRegistry,
    private val scheduler: com.astarworks.astera.core.application.port.outbound.IScheduler,
) : Listener {

    private val zones = CopyOnWriteArrayList<LegacyZone>()

    /** Untagged [TextDisplay]s within [radius] blocks of [center] in [world] are legacy duplicates. */
    public data class LegacyZone(val world: WorldId, val center: Vec3, val radius: Double)

    public fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        PaperManagedEntities.purge(plugin.server, scheduler)
    }

    public fun stop() {
        PaperManagedEntities.purge(plugin.server, scheduler)
    }

    /** Registers [zone] and sweeps it right away in the already loaded chunks. */
    public fun addLegacyZone(zone: LegacyZone) {
        if (zone !in zones) zones += zone
        val world = plugin.server.getWorld(zone.world.value) ?: return
        sweepZone(world.entities, zone)
    }

    @EventHandler
    public fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) onOwner(entity) { removeIfStale(entity) }
        zones.filter { it.world.value == event.world.name }.forEach { sweepZone(event.entities, it) }
    }

    @EventHandler
    public fun onWorldLoad(event: WorldLoadEvent) {
        for (entity in event.world.entities) onOwner(entity) { removeIfStale(entity) }
    }

    private fun removeIfStale(entity: Entity) {
        if (PaperManagedEntities.isManaged(entity) && !registry.isClaimed(entity.uniqueId)) entity.remove()
    }

    private fun sweepZone(entities: Collection<Entity>, zone: LegacyZone) {
        for (entity in entities) onOwner(entity) {
            if (entity !is TextDisplay || PaperManagedEntities.isManaged(entity) || !entity.isValid) return@onOwner
            val at = entity.location
            val dx = at.x - zone.center.x
            val dy = at.y - zone.center.y
            val dz = at.z - zone.center.z
            if (dx * dx + dy * dy + dz * dz <= zone.radius * zone.radius) entity.remove()
        }
    }

    private fun onOwner(entity: Entity, action: () -> Unit) {
        scheduler.run(com.astarworks.astera.core.application.port.outbound.ExecutionScope.Entity(entity.uniqueId), kotlin.time.Duration.ZERO, action)
    }
}

package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import io.papermc.paper.event.entity.EntityMoveEvent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import kotlin.math.floor
import kotlin.time.Duration

/**
 * World-scoped, chunk-keyed broad phase. World slots occupy disjoint chunk ranges,
 * so instance queries share no buckets. Only immutable snapshots cross owners.
 * Index the entire hitbox, including oversized mobs straddling chunk boundaries.
 */
public class CombatSpatialGrid : Listener {
    public var onMove: (UUID, WorldId, Vec3) -> Unit = { _, _, _ -> }
    public data class Snapshot(
        val world: WorldId,
        val id: PlayerId,
        val feet: Vec3,
        val height: Double,
        val min: Vec3,
        val max: Vec3,
        val damageable: Boolean,
        val health: Double,
        val maxHealth: Double,
        val pose: com.astarworks.astera.core.domain.player.PlayerPose,
    ) {
        public fun nearby(): NearbyTarget = NearbyTarget(id, feet, height)
    }
    private data class Chunk(val world: WorldId, val x: Int, val z: Int)
    private val buckets = ConcurrentHashMap<Chunk, ConcurrentSkipListMap<UUID, Snapshot>>()
    private val references = ConcurrentHashMap<UUID, LivingEntity>()
    private val entities = ConcurrentHashMap<UUID, Snapshot>()

    public fun start(plugin: JavaPlugin, scheduler: IScheduler) {
        plugin.server.pluginManager.registerEvents(this, plugin)
        // Enable-time bootstrap only. Chunk entity reads run on their owner.
        for (world in plugin.server.worlds) for (chunk in world.loadedChunks) {
            scheduler.run(ExecutionScope.Region(WorldId(world.name), chunk.x, chunk.z), Duration.ZERO) {
                if (chunk.isLoaded) chunk.entities.filterIsInstance<LivingEntity>().forEach { update(it) }
            }
        }
    }

    public fun clear() { buckets.clear(); entities.clear(); references.clear() }

    public fun update(entity: LivingEntity, at: Location = entity.location) {
        check(Bukkit.isOwnedByCurrentRegion(entity)) { "Spatial grid update outside entity owner" }
        references[entity.uniqueId] = entity
        val location = entity.location
        val delta = at.toVector().subtract(location.toVector())
        val box = entity.boundingBox.shift(delta)
        val eye = entity.eyeLocation.toVector().add(delta)
        val dir = at.direction
        val next = Snapshot(WorldId(at.world.name), PlayerId(entity.uniqueId), Vec3(at.x, at.y, at.z), entity.height,
            Vec3(box.minX, box.minY, box.minZ), Vec3(box.maxX, box.maxY, box.maxZ), damageable(entity), entity.health,
            entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: entity.health,
            com.astarworks.astera.core.domain.player.PlayerPose(WorldId(at.world.name), Vec3(at.x, at.y, at.z),
                Vec3(eye.x, eye.y, eye.z), Vec3(dir.x, dir.y, dir.z).normalize().takeIf { it.lengthSquared() > 0.0 } ?: Vec3(0.0, 0.0, 1.0)))
        val old = entities.put(entity.uniqueId, next)
        if (old == next) return
        if (old != null) for (key in chunks(old)) removeFromBucket(key, entity.uniqueId)
        for (key in chunks(next)) buckets.compute(key) { _, bucket ->
            (bucket ?: ConcurrentSkipListMap()).also { it[entity.uniqueId] = next }
        }
        if (old?.feet != next.feet || old.world != next.world) onMove(entity.uniqueId, next.world, next.feet)
        ExecutionCosts.increment("combat_grid_updates")
    }

    public fun candidates(world: WorldId?, min: Vec3, max: Vec3): List<Snapshot> =
        ExecutionCosts.measure("combat.grid_query", "all") {
            val worlds = world?.let { listOf(it) } ?: entities.values.map { it.world }.distinct()
            val found = java.util.TreeMap<UUID, Snapshot>()
            for (w in worlds) for (x in chunk(min.x)..chunk(max.x)) for (z in chunk(min.z)..chunk(max.z)) {
                buckets[Chunk(w, x, z)]?.forEach { (id, snapshot) -> found[id] = snapshot }
            }
            found.values.filter { s ->
                s.max.x >= min.x && s.min.x <= max.x && s.max.y >= min.y && s.min.y <= max.y &&
                    s.max.z >= min.z && s.min.z <= max.z
            }
        }

    public fun snapshot(id: UUID): Snapshot? = entities[id]
    public fun entity(id: UUID): LivingEntity? = references[id]
    public fun refreshLocal(snapshot: Snapshot): Snapshot {
        val entity = references[snapshot.id.uuid] ?: return snapshot
        if (Bukkit.isOwnedByCurrentRegion(entity)) update(entity)
        return entities[snapshot.id.uuid] ?: snapshot
    }

    private fun chunks(s: Snapshot): List<Chunk> = buildList {
        for (x in chunk(s.min.x)..chunk(s.max.x)) for (z in chunk(s.min.z)..chunk(s.max.z)) add(Chunk(s.world, x, z))
    }
    private fun chunk(value: Double): Int = floor(value).toInt() shr 4
    private fun remove(id: UUID) {
        references.remove(id)
        val old = entities.remove(id) ?: return
        for (key in chunks(old)) removeFromBucket(key, id)
    }
    private fun removeFromBucket(key: Chunk, id: UUID) {
        buckets.computeIfPresent(key) { _, bucket ->
            bucket.remove(id)
            bucket.takeUnless { it.isEmpty() }
        }
    }
    private fun damageable(e: LivingEntity): Boolean = e.isValid && !e.isDead && when (e) {
        is Player -> e.gameMode != GameMode.SPECTATOR
        is ArmorStand -> false
        else -> !e.isInvulnerable
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun added(e: EntityAddToWorldEvent) { (e.entity as? LivingEntity)?.let { update(it) } }
    @EventHandler(priority = EventPriority.MONITOR)
    public fun removed(e: EntityRemoveFromWorldEvent) { remove(e.entity.uniqueId) }
    @EventHandler(priority = EventPriority.MONITOR)
    public fun loaded(e: EntitiesLoadEvent) { e.entities.filterIsInstance<LivingEntity>().forEach { update(it) } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun moved(e: EntityMoveEvent) { update(e.entity, e.to) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun playerMoved(e: PlayerMoveEvent) { update(e.player, e.to) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun entityTeleported(e: org.bukkit.event.entity.EntityTeleportEvent) {
        val entity = e.entity as? LivingEntity ?: return
        e.to?.let { update(entity, it) }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun teleported(e: PlayerTeleportEvent) { update(e.player, e.to) }
}

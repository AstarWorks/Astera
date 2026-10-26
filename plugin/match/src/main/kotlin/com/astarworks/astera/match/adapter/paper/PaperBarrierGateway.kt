package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.domain.barrier.Barrier
import com.astarworks.astera.match.domain.barrier.BarrierStatus
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.adapter.paper.world.assertWorldOwnership
import kotlin.time.Duration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.concurrent.ConcurrentHashMap

/**
 * Global owns the desired barrier state. Each anchor's Region reconciles a single
 * visual BlockDisplay in [Barrier.worldId]; subsequent entity edits use Entity.
 * Pending callbacks read the latest desired state so a queued spawn cannot resurrect
 * a despawned barrier or overwrite a newer status. Displays are visual only; domain
 * barrier checks supply damage absorption and do not depend on spawn completion.
 */
public class PaperBarrierGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    @Suppress("unused") private val eventBus: IDomainEventBus,
) : IBarrierGateway {

    private val store: ConcurrentHashMap<BarrierId, BarrierTracking> = ConcurrentHashMap()

    private val displays = ConcurrentHashMap<BarrierId, BlockDisplay>()

    override fun spawn(barrier: Barrier) {
        store[barrier.id] = BarrierTracking(barrier, BarrierStatus.Active)
        reconcile(barrier)
    }

    override fun despawn(id: BarrierId) {
        val tracking = store.remove(id) ?: return
        reconcile(tracking.barrier)
    }

    override fun updateStatus(id: BarrierId, status: BarrierStatus) {
        val current = store[id] ?: return
        if (status == BarrierStatus.Destroyed) store.remove(id)
        else store[id] = current.copy(status = status)
        reconcile(current.barrier)
    }

    override fun all(): List<Barrier> = store.values.map { it.barrier }

    private fun reconcile(barrier: Barrier) {
        val scope = regionScope(barrier.worldId, barrier.region.min)
        scheduler.run(scope, Duration.ZERO) {
            assertWorldOwnership(scheduler, scope)
            val current = store[barrier.id]
            val display = displays[barrier.id]
            if (display != null) {
                scheduler.run(ExecutionScope.Entity(display.uniqueId), Duration.ZERO) {
                    assertWorldOwnership(scheduler, ExecutionScope.Entity(display.uniqueId))
                    val latest = store[barrier.id]
                    if (latest == null) {
                        displays.remove(barrier.id, display)
                        display.remove()
                    } else display.block = materialFor(latest.status)
                }
            } else if (current != null) {
                val world = server.getWorld(barrier.worldId.value) ?: return@run
                val anchor = barrier.region.min
                val spawned = world.spawn(Location(world, anchor.x, anchor.y, anchor.z), BlockDisplay::class.java) {
                    com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(it, "barrier")
                    it.block = materialFor(current.status)
                    it.transformation = scaleTransformationFor(barrier.region.min, barrier.region.max)
                    it.viewRange = DEFAULT_VIEW_RANGE
                }
                displays[barrier.id] = spawned
                // Global may have changed the desired state during the spawn.
                val latest = store[barrier.id]
                if (latest == null) {
                    displays.remove(barrier.id, spawned)
                    spawned.remove()
                } else spawned.block = materialFor(latest.status)
            }
        }
    }

    private fun materialFor(status: BarrierStatus): BlockData = when (status) {
        BarrierStatus.Active -> Bukkit.createBlockData(Material.BARRIER)
        BarrierStatus.Disabled -> Bukkit.createBlockData(Material.GLASS)
        // Destroyed is handled by entity removal, but the function stays
        // total so a future caller cannot pass a status the visual mapper
        // forgets to handle.
        BarrierStatus.Destroyed -> Bukkit.createBlockData(Material.AIR)
    }

    private fun scaleTransformationFor(min: Vec3, max: Vec3): Transformation {
        val sx = (max.x - min.x).toFloat().coerceAtLeast(MIN_DISPLAY_EXTENT)
        val sy = (max.y - min.y).toFloat().coerceAtLeast(MIN_DISPLAY_EXTENT)
        val sz = (max.z - min.z).toFloat().coerceAtLeast(MIN_DISPLAY_EXTENT)
        return Transformation(
            Vector3f(0f, 0f, 0f),
            AxisAngle4f(0f, 0f, 1f, 0f),
            Vector3f(sx, sy, sz),
            AxisAngle4f(0f, 0f, 1f, 0f),
        )
    }

    private data class BarrierTracking(
        val barrier: Barrier,
        val status: BarrierStatus,
    )

    public companion object {
        /**
         * `Display.viewRange` multiplier. Bukkit treats this as a multiplier
         * over the client's render distance — `64` here means "always
         * render while the chunk is loaded", which matches the gameplay
         * importance of barrier visibility in a match.
         */
        public const val DEFAULT_VIEW_RANGE: Float = 64f

        /**
         * Minimum scale for a [BlockDisplay] transformation. A scale of
         * exactly `0f` collapses the rendered mesh to a single point;
         * coercing to a small positive value keeps a debugging fallback
         * visible if a zero-volume region ever reaches the gateway (the
         * use case rejects empty regions, but this stays defensive).
         */
        public const val MIN_DISPLAY_EXTENT: Float = 0.01f
    }
}

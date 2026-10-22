package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.barrier.BarrierId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IBarrierGateway
import com.astarworks.astera.match.domain.barrier.Barrier
import com.astarworks.astera.match.domain.barrier.BarrierStatus
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IBarrierGateway] (Wave 6b-1, ADR-0156) — backs each
 * [Barrier] aggregate with a single Bukkit `BlockDisplay` entity whose
 * scaled transformation covers the barrier's [com.astarworks.astera.core.domain.stage.Region].
 *
 * Mirrors `InMemoryBarrierGateway`'s state shape (a `Map<BarrierId, Barrier>`)
 * so the Fake / Paper pair documented on [IBarrierGateway] stay observably
 * interchangeable for use cases. Adds a parallel tracking map of the
 * spawned Bukkit entity UUID so [despawn] can look up and remove the
 * `BlockDisplay` without scanning every world.
 *
 * # Visual mapping
 *
 *  - [BarrierStatus.Active]   → `Material.BARRIER` block data. Vanilla barrier
 *    blocks render the standard translucent red "X" only in creative; in
 *    survival they are fully invisible. Phase 1 PoC ships this as-is — the
 *    visible silhouette is non-goal; the gameplay effect (damage absorption
 *    via Combat's barrier integration) is what the visual represents.
 *  - [BarrierStatus.Disabled] → `Material.GLASS` block data. Translucent so
 *    players can see the barrier is present but the active-status absorption
 *    no longer applies. Hot-swap via `BlockDisplay.block = ...`.
 *  - [BarrierStatus.Destroyed] → entity removed (same path as [despawn]).
 *    The aggregate is also dropped from internal tracking so a follow-up
 *    [all] does not surface a phantom Destroyed barrier.
 *
 * # Region → Transformation
 *
 * Each barrier spawns one [BlockDisplay] anchored at [Barrier.origin]
 * (lower-corner of the region per `CreateBarrierUseCase`). The
 * `Transformation.scale` is set to the region's extent so a single block
 * mesh stretches across the entire enclosure volume — far cheaper than
 * one entity per filled position. The `leftRotation` is identity
 * (`AxisAngle4f(0f, 0f, 1f, 0f)`); rotated barriers are a Phase 6 stretch
 * once Match has rotated-region support.
 *
 * # Phase 1 limitations
 *
 *  - **Visual only, no server-side collision.** `Material.BARRIER`'s native
 *    collision applies only when the block is placed in the world grid; a
 *    `BlockDisplay`-projected barrier is a render entity, not a block, so
 *    players walk through it. Real block enforcement (or region-plugin
 *    collision via WorldGuard / similar) lands when ADR-0156 supersedes
 *    this Phase 1 PoC. The damage-absorption gameplay rule is still
 *    enforced by Combat using the [Barrier] aggregate's region check; the
 *    visual is purely informational.
 *  - **Multi-world by `Barrier.worldId`.** Each [Barrier] carries its own
 *    [WorldId] (Wave 6c, ADR-0160). This gateway resolves the target
 *    Bukkit world via `server.getWorld(barrier.worldId.value)`. If that
 *    world is not currently loaded, [spawn] tracks the aggregate without
 *    a visual entity (logged at DEBUG); [despawn] / [updateStatus] are
 *    no-ops for the entity side while still mutating the in-memory state.
 *    Two concurrent matches in different worlds can hold barriers at the
 *    same `x/y/z` anchor without cross-world collision because each
 *    `BlockDisplay` is spawned inside its barrier's own world.
 *  - **Single BlockDisplay per barrier.** Phase 1 PoC does not subdivide
 *    the region into per-block visuals; a sub-region "carve" feature
 *    (matching the BARRIER-grid that a survival player would see if the
 *    blocks were placed) lands when ADR-0156 is revisited.
 *
 * # Threading
 *
 * Bukkit world / entity APIs require the server main thread. This gateway
 * is built for callers already on main (the match lifecycle and
 * `BarrierStatusScheduler` both run on the scheduler's main dispatcher per
 * `IScheduler` contract). The internal map is a [ConcurrentHashMap] only
 * as a defence-in-depth against unexpected concurrent reads from
 * monitoring / HUD subscribers, not because off-main mutation is
 * supported.
 *
 * # Offline world handling
 *
 * If `server.worlds` is empty (no world loaded — unusual outside test
 * harnesses, but possible mid-restart) [spawn] logs at DEBUG and tracks
 * the barrier in memory without a Bukkit entity. Subsequent [despawn] is
 * a no-op for the Bukkit side; the in-memory state is still cleaned up so
 * [all] stays consistent. There is no `Result<T, E>` return on the port
 * methods (they are `Unit`), so failures cannot surface to the use case —
 * this matches `InMemoryBarrierGateway` and follows the port's "no
 * visible failure mode" contract.
 *
 * # Wire-up
 *
 * Register in `AsteraMatchPlugin.onEnable` at priority 100 to overlay
 * the default `InMemoryBarrierGateway` registration:
 *
 *     services.register<IBarrierGateway>(
 *         PaperBarrierGateway(server, scheduler, eventBus),
 *         priority = 100,
 *     )
 *
 * The same `scheduler` + `eventBus` already wired into Match
 * (`AsteraMatchPlugin` resolves both from the `ServiceRegistry` at
 * `onEnable`). The constructor reserves the scheduler / eventBus
 * dependencies so a future status-change `BarrierStatusChanged` event
 * publish — anticipated in ADR-0156 — does not require a port-shape
 * revision.
 *
 * @see IBarrierGateway
 * @see com.astarworks.astera.match.application.service.InMemoryBarrierGateway
 */
public class PaperBarrierGateway(
    private val server: Server,
    @Suppress("unused") private val scheduler: IScheduler,
    @Suppress("unused") private val eventBus: IDomainEventBus,
) : IBarrierGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    private val store: ConcurrentHashMap<BarrierId, BarrierTracking> = ConcurrentHashMap()

    override fun spawn(barrier: Barrier) {
        val world = resolveWorld()
        val entityUuid = if (world == null) {
            log.debug(
                "PaperBarrierGateway.spawn: no world loaded; tracking barrier {} without visual",
                barrier.id.value,
            )
            null
        } else {
            spawnDisplay(world, barrier, BarrierStatus.Active)
        }
        store[barrier.id] = BarrierTracking(
            barrier = barrier,
            status = BarrierStatus.Active,
            entityUuid = entityUuid,
        )
    }

    override fun despawn(id: BarrierId) {
        val tracking = store.remove(id) ?: return
        removeBukkitEntity(tracking.entityUuid)
    }

    override fun updateStatus(id: BarrierId, status: BarrierStatus) {
        val current = store[id] ?: return
        when (status) {
            BarrierStatus.Destroyed -> {
                store.remove(id)
                removeBukkitEntity(current.entityUuid)
            }
            else -> {
                store[id] = current.copy(status = status)
                applyStatusVisual(current.entityUuid, status)
            }
        }
    }

    override fun all(): List<Barrier> = store.values.map { it.barrier }

    // --- Helpers -----------------------------------------------------------

    private fun resolveWorld(): World? = server.worlds.firstOrNull()

    private fun spawnDisplay(world: World, barrier: Barrier, status: BarrierStatus): UUID? {
        val anchor = barrier.region.min
        val location = Location(world, anchor.x, anchor.y, anchor.z)
        val display = world.spawn(location, BlockDisplay::class.java)
        com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(display, "barrier")
        display.block = materialFor(status)
        display.transformation = scaleTransformationFor(barrier.region.min, barrier.region.max)
        // Long view range so players see the barrier from across the map.
        display.viewRange = DEFAULT_VIEW_RANGE
        return display.uniqueId
    }

    private fun applyStatusVisual(entityUuid: UUID?, status: BarrierStatus) {
        if (entityUuid == null) return
        val display = server.getEntity(entityUuid) as? BlockDisplay ?: return
        display.block = materialFor(status)
    }

    private fun removeBukkitEntity(entityUuid: UUID?) {
        if (entityUuid == null) return
        server.getEntity(entityUuid)?.remove()
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

    /**
     * Bukkit entity UUID + last-known [BarrierStatus] for one tracked
     * barrier. Kept as a value type so the [ConcurrentHashMap] holds one
     * reference per id and snapshot reads through [all] do not race with
     * status updates.
     */
    private data class BarrierTracking(
        val barrier: Barrier,
        val status: BarrierStatus,
        val entityUuid: UUID?,
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

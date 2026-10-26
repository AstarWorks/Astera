package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.combat.application.port.outbound.IProjectileHandle
import com.astarworks.astera.combat.application.port.outbound.IProjectileService
import com.astarworks.astera.combat.application.service.ProjectileFlight
import com.astarworks.astera.combat.application.service.ProjectileImpactResolver
import com.astarworks.astera.combat.application.service.ProjectileStepper
import com.astarworks.astera.combat.domain.projectile.DisplaySpec
import com.astarworks.astera.combat.domain.projectile.DisplayVariant
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Paper concrete of [IProjectileService]: **server-simulated projectiles with
 * no vanilla projectile entity** (F2-4; supersedes the Snowball-carrier design
 * of ADR-0155 / ADR-0149 §C).
 *
 * Every tick the shared [ProjectileStepper] — the same code the headless game
 * simulator runs — advances each flight: velocity integration (homing steer,
 * gravity, drag), a swept-segment contact test through [PaperProjectileProbe]
 * (`World#rayTraceBlocks` + `World#rayTraceEntities`, owner excluded), lifetime
 * expiry, the particle trail, and the exactly-once call into
 * [ProjectileImpactResolver] (`onHitEntity` / `onHitBlock` / `onExpire`).
 * Damage, status, sounds, particles, WP10 structure hits and the skill's
 * `hit_*` / `miss` / `expire` phases all run there, so nothing here touches
 * Bukkit damage and no vanilla `Projectile` ever exists (players no longer see
 * snowballs).
 *
 * # Visuals
 *
 *  - particle trail: emitted by the stepper through `IWorldGateway.spawnParticle`;
 *  - `display`: an `ItemDisplay` / `BlockDisplay` / `TextDisplay` teleported to the
 *    projectile every tick with a 1-tick teleport duration (client-side
 *    interpolation) and removed on impact, expiry, cancel or [stop];
 *  - neither: nothing is visible.
 *
 * # Threading
 *
 * Chunk-anchored phases read and mutate the world on its owning region.
 * Only immutable motion arithmetic is forked; contacts/impacts merge in UUID order.
 */
public class PaperProjectileService(
    private val server: Server,
    private val grid: CombatSpatialGrid,
    private val phase: PaperCombatPhase,
    private val compute: com.astarworks.astera.combat.application.service.CombatChunking,
    private val scheduler: IScheduler,
    teamLookup: () -> IMatchTeamLookup?,
) : IProjectileService {

    private val log = LoggerFactory.getLogger(javaClass)

    /** One live projectile: the simulated flight plus its optional display entity. */
    private class Live(val flight: ProjectileFlight, val world: World, var displayUuid: UUID?) {
        var lastStepTick: Long = Long.MIN_VALUE
    }

    private val live = java.util.concurrent.ConcurrentHashMap<UUID, Live>()

    private var tickHandle: IScheduler.TaskHandle? = null

    /**
     * Host-neutral impact logic (ADR-0174). Bound after construction because
     * the resolver is built from the combat use cases, which are wired after
     * this service. `null` => impacts just end the flight (no effects).
     */
    private var impactResolver: ProjectileImpactResolver? = null

    private val stepper = ProjectileStepper({ impactResolver })

    private val probe = PaperProjectileProbe(grid, teamLookup) { flight ->
        live[flight.id.uuid]?.world
    }

    /** Bind the resolver that runs the impact / expire phases. */
    public fun bindImpactResolver(resolver: ProjectileImpactResolver) {
        impactResolver = resolver
    }

    /**
     * Wire the per-tick stepper. Returns the handle so the plugin can cancel it
     * on `onDisable`. Idempotent — calling [start] twice cancels the previous handle.
     */
    public fun start(): IScheduler.TaskHandle {
        tickHandle?.cancel()
        phase.tickProjectiles = ::tickBatch
        val handle = object : IScheduler.TaskHandle {
            private var cancelled = false
            override fun cancel() { cancelled = true; phase.tickProjectiles = {} }
            override val isCancelled: Boolean get() = cancelled
            override fun onRetired(cb: () -> Unit) = Unit
        }
        tickHandle = handle
        return handle
    }

    /** Cancel the stepper and remove every live display entity. */
    public fun stop() {
        tickHandle?.cancel()
        tickHandle = null
        for (entry in live.values.toList()) {
            phase.removeProjectile(entry.flight.id.uuid)
            scheduler.run(regionScope(com.astarworks.astera.core.domain.world.WorldId(entry.world.name), entry.flight.position), kotlin.time.Duration.ZERO) { removeDisplay(entry) }
        }
        live.clear()
    }

    override fun spawn(
        spec: ProjectileSpec,
        origin: Vec3,
        direction: Vec3,
        owner: PlayerId,
    ): IProjectileHandle {
        // The caster's world (from the cast pose), else the owner's current world, else the first loaded one.
        val world = spec.skill?.worldId?.let { server.getWorld(it.value) }
            ?: server.getPlayer(owner.uuid)?.world
            ?: server.worlds.firstOrNull()
        if (world == null) {
            log.warn("PaperProjectileService.spawn: no loaded world; dropping spawn for owner={}", owner)
            return DeadHandle(ProjectileId.random())
        }
        val flight = ProjectileFlight(
            id = ProjectileId.random(),
            owner = owner,
            spec = spec,
            position = origin,
            velocity = stepper.launchVelocity(spec, direction),
            spawnTick = scheduler.currentTick(),
        )
        val entry = Live(flight, world, displayUuid = null)
        spec.visual.display?.let { entry.displayUuid = spawnDisplay(world, it, origin, flight.velocity) }
        live[flight.id.uuid] = entry
        phase.projectile(flight.id.uuid, com.astarworks.astera.core.domain.world.WorldId(world.name), origin)
        return LiveHandle(flight.id, entry)
    }

    private fun tickBatch(ids: List<UUID>) {
        val now = scheduler.currentTick()
        val entries = ids.mapNotNull { live[it] }.filter { it.lastStepTick != now }
        val plans = compute.map(entries.map { entry ->
            val f = entry.flight
            com.astarworks.astera.combat.domain.compute.ProjectileMotionInput(f.position, f.velocity, f.spec.motion)
        }, com.astarworks.astera.combat.domain.compute.ProjectileMotion::compute)
        for ((index, entry) in entries.withIndex()) {
            if (live[entry.flight.id.uuid] !== entry) continue
            entry.lastStepTick = now
            val p = entry.flight.position
            check(server.isOwnedByCurrentRegion(entry.world, kotlin.math.floor(p.x).toInt() shr 4, kotlin.math.floor(p.z).toInt() shr 4))
            try {
                val outcome = stepper.step(entry.flight, now, probe, motion = plans[index]) { remove(entry) }
                if (!outcome.isTerminal) {
                    moveDisplay(entry)
                    phase.projectile(entry.flight.id.uuid, com.astarworks.astera.core.domain.world.WorldId(entry.world.name), entry.flight.position)
                }
            } catch (ex: Exception) {
                log.warn("Projectile step failed for {}; dropping it", entry.flight.id, ex)
                remove(entry)
            }
        }
    }

    private fun remove(entry: Live) {
        live.remove(entry.flight.id.uuid, entry)
        phase.removeProjectile(entry.flight.id.uuid)
        removeDisplay(entry)
    }

    // ---- display entity -------------------------------------------------

    private fun spawnDisplay(world: World, display: DisplaySpec, at: Vec3, velocity: Vec3): UUID? =
        try {
            val loc = Location(world, at.x, at.y, at.z).apply { setDirection(velocity.toBukkit()) }
            val entity: Display = when (display.variant) {
                DisplayVariant.ITEM_DISPLAY -> world.spawn(loc, ItemDisplay::class.java) {
                    it.setItemStack(ItemStack(materialOf(display.materialKey)))
                }
                DisplayVariant.BLOCK_DISPLAY -> world.spawn(loc, BlockDisplay::class.java) {
                    it.block = materialOf(display.materialKey).createBlockData()
                }
                DisplayVariant.TEXT_DISPLAY -> world.spawn(loc, TextDisplay::class.java) {
                    it.text(Component.text(display.materialKey))
                    it.isShadowed = true
                }
            }
            com.astarworks.astera.core.adapter.paper.entity.PaperManagedEntities.mark(entity, "projectile-display")
            entity.setGravity(false)
            entity.teleportDuration = DISPLAY_TELEPORT_TICKS
            entity.billboard = Display.Billboard.valueOf(display.billboard.name)
            entity.transformation = Transformation(
                Vector3f(),
                AxisAngle4f(),
                Vector3f(display.scale.x.toFloat(), display.scale.y.toFloat(), display.scale.z.toFloat()),
                AxisAngle4f(),
            )
            entity.uniqueId
        } catch (t: Throwable) {
            log.warn("PaperProjectileService: could not spawn {} display for {}", display.variant, display.materialKey, t)
            null
        }

    private fun moveDisplay(entry: Live) {
        val uuid = entry.displayUuid ?: return
        val entity = server.getEntity(uuid)
        if (entity == null) {
            entry.displayUuid = null
            return
        }
        val p = entry.flight.position
        val loc = Location(entry.world, p.x, p.y, p.z).apply { setDirection(entry.flight.velocity.toBukkit()) }
        scheduler.run(ExecutionScope.Entity(uuid), kotlin.time.Duration.ZERO) {
            check(server.isOwnedByCurrentRegion(entity))
            if (!entity.isDead) entity.teleportAsync(loc)
        }
    }

    private fun removeDisplay(entry: Live) {
        val uuid = entry.displayUuid ?: return
        entry.displayUuid = null
        val entity = server.getEntity(uuid)
        if (entity != null) scheduler.run(ExecutionScope.Entity(uuid), kotlin.time.Duration.ZERO) {
            check(server.isOwnedByCurrentRegion(entity))
            if (!entity.isDead) entity.remove()
        }
    }

    private fun materialOf(key: String): Material =
        Material.matchMaterial(key) ?: Material.STONE.also {
            log.warn("PaperProjectileService: unknown display material '{}'; using stone", key)
        }

    private fun Vec3.toBukkit(): org.bukkit.util.Vector =
        if (lengthSquared() == 0.0) org.bukkit.util.Vector(0.0, 0.0, 1.0) else org.bukkit.util.Vector(x, y, z)

    private inner class LiveHandle(override val id: ProjectileId, private val entry: Live) : IProjectileHandle {
        override val isAlive: Boolean get() = live[id.uuid] === entry
        override val position: Vec3? get() = entry.flight.position
        override fun cancel() {
            scheduler.run(regionScope(com.astarworks.astera.core.domain.world.WorldId(entry.world.name), entry.flight.position), kotlin.time.Duration.ZERO) {
                if (live[id.uuid] === entry) remove(entry)
            }
        }
    }

    /** Returned when no world exists to fly in; reports `isAlive = false`. */
    private class DeadHandle(override val id: ProjectileId) : IProjectileHandle {
        override val isAlive: Boolean = false
        override fun cancel() = Unit
    }

    public companion object {
        /** Client-side interpolation window of the display entity (one tick = smooth per-tick motion). */
        public const val DISPLAY_TELEPORT_TICKS: Int = 1
    }
}

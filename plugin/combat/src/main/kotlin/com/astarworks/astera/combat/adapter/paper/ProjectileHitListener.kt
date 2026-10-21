package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.service.ProjectileImpactResolver
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Wave 6a-N2 / ADR-0149 §C — Astera-side projectile impact listener.
 *
 * # Why this exists
 *
 * [PaperProjectileService] spawns a Bukkit Projectile entity as a *visual
 * carrier* only — every damage / status / particle / child-spawn the projectile
 * triggers is resolved by Astera's domain pipeline, not by vanilla Bukkit
 * damage maths. This listener is the bridge: it observes Bukkit's collision
 * events, looks up the Astera-side tracking entry, and hands the impact to
 * [ProjectileImpactResolver].
 *
 * Pattern reference: [com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter]
 * (Wave 6a-6 / ADR-0152). That adapter cancels vanilla `EntityDamageEvent`
 * environment causes and re-routes through `IPlayerHealthGateway`. This
 * listener does the analogous re-route for Astera-spawned projectile damage
 * so vanilla armor / Protection / Resistance / Absorption math does not
 * double-count Astera's `ArmorReductionModifier` (the same `PaperDamageGateway`
 * invariant under ADR-0149 §C the gateway itself enforces in non-projectile
 * paths).
 *
 * # Roles
 *
 *  - **Role 1: onProjectileHit** — observes the projectile's terminal
 *    `ProjectileHitEvent` (entity hit or block hit). Looks up Astera tracking
 *    by the Bukkit projectile UUID; if absent, the projectile was not spawned
 *    by Astera (vanilla skeleton arrow, wandering trader, etc.) and the
 *    listener returns immediately so other plugins own the hit. If present,
 *    the spec's `onHit` list is dispatched: `DealDamage` -> ApplyDamageUseCase,
 *    `InflictStatus` -> ApplyStatusUseCase. After dispatch the tracking entry
 *    is untracked.
 *
 *  - **Role 2: onEntityDamageByEntity** — defensive cancellation. When the
 *    damager is an Astera-tracked projectile, the vanilla damage event is
 *    cancelled (the Role 1 path is authoritative). When the damager is a
 *    non-Astera projectile, the listener is silent and the core
 *    `BukkitEventAdapter` Role 2 cancels + re-routes environment intrusion as
 *    appropriate.
 *
 * # Dispatch (ADR-0174)
 *
 * All impact logic lives in the host-neutral [ProjectileImpactResolver]
 * (spec `onHit` effects + the skill's `hit_entity` / `hit_block` / `miss`
 * phases); this class only translates Bukkit events into
 * `onHitEntity` / `onHitBlock` calls. Lifetime expiry is reported by
 * [PaperProjectileService]'s watchdog, not here.
 *
 * # Threading
 *
 * Bukkit dispatches events on the main thread; this listener runs synchronously
 * on main and never touches asynchronous state. ApplyDamageUseCase /
 * ApplyStatusUseCase already run on main when invoked from event handlers per
 * the existing combat call-chain convention.
 *
 * # Registration
 *
 * Construct in `AsteraCombatPlugin.onEnable` after the projectile service is
 * built and call [start] with the plugin instance to wire the
 * `@EventHandler`s. No teardown handle is needed — Paper invokes
 * `HandlerList.unregisterAll(plugin)` during normal `onDisable`.
 */
public class ProjectileHitListener(
    private val projectileService: PaperProjectileService,
    private val impactResolver: ProjectileImpactResolver,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Register the listener on [plugin]'s event bus. Returns this so the call site can chain. */
    public fun start(plugin: JavaPlugin): ProjectileHitListener {
        plugin.server.pluginManager.registerEvents(this, plugin)
        return this
    }

    // ── Role 1: Astera-tracked projectile hit ─────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile: Projectile = event.entity
        val tracking = projectileService.trackingForBukkitEntity(projectile.uniqueId) ?: return
        val hitEntity = event.hitEntity
        try {
            val flight = tracking.toFlight()
            if (hitEntity != null) {
                val target = (hitEntity as? Player)?.let { PlayerId(it.uniqueId) }
                val at = Vec3(hitEntity.location.x, hitEntity.location.y, hitEntity.location.z)
                impactResolver.onHitEntity(flight, target, at)
            } else {
                // Block hit (or an impact with neither entity nor block: treat as
                // a block hit at the carrier's position).
                val at = Vec3(projectile.location.x, projectile.location.y, projectile.location.z)
                impactResolver.onHitBlock(flight, at)
            }
        } catch (t: Throwable) {
            log.warn(
                "ProjectileHitListener.onProjectileHit: dispatch failed for projectile={} owner={}",
                tracking.id,
                tracking.ownerId,
                t,
            )
        } finally {
            // The impact terminates the visual carrier (vanilla removes
            // arrow/snowball post-collision); untrack exactly once.
            projectileService.untrack(tracking.id)
        }
    }

    // ── Role 2: vanilla EntityDamageByEntity cancellation for Astera projectiles ──

    // LOWEST so the cancel lands before core's BukkitEventAdapter (LOW), which
    // re-routes un-cancelled PROJECTILE damage into IPlayerHealthGateway.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager !is Projectile) return
        // Astera-tracked projectile: cancel the vanilla damage write. The
        // canonical damage application happens in onProjectileHit via
        // ApplyDamageUseCase. Without this cancel, Bukkit's vanilla damage
        // path would apply (a) a fixed arrow/snowball damage number, and (b)
        // vanilla armor / Protection enchantment math — both of which
        // double-count against Astera's PaperDamageGateway + ArmorReductionModifier
        // pipeline (ADR-0149 §C: Astera-internal HP is source of truth).
        //
        // Only Player victims: Astera's IDamageGateway is player-keyed, so
        // dispatchOnHit skips non-player targets. Cancelling for a mob would
        // make the hit deal zero damage; leave the vanilla hit (applied once)
        // until a mob damage port exists.
        if (event.entity is Player && projectileService.trackingForBukkitEntity(damager.uniqueId) != null) {
            event.isCancelled = true
            return
        }
        // Non-Astera projectile (vanilla mob arrow, wandering trader, etc.).
        // The core BukkitEventAdapter Role 2 owns the environment-intrusion
        // cancel + re-route for non-Astera damage paths; this listener stays
        // silent so we do not double-handle.
    }
}

package com.astarworks.astera.combat.adapter.paper.aoe

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.aoe.IAreaEffectGateway
import com.astarworks.astera.combat.domain.aoe.AreaEffectAction
import com.astarworks.astera.combat.domain.aoe.AreaEffectError
import com.astarworks.astera.combat.domain.aoe.AreaEffectHandle
import com.astarworks.astera.combat.domain.aoe.AreaEffectShape
import com.astarworks.astera.combat.domain.aoe.AreaEffectSpec
import com.astarworks.astera.combat.domain.event.aoe.AreaEffectDespawned
import com.astarworks.astera.combat.domain.event.aoe.AreaEffectSpawned
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.IDamageApplier
import com.astarworks.astera.core.application.port.outbound.effect.IStatusEffectApplier
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import org.bukkit.Server
import java.util.UUID
import kotlin.time.Duration

/**
 * Paper concrete of [IAreaEffectGateway] (Wave 6e, ADR-0162).
 *
 * Tick-driven entity-in-shape query + action dispatch via
 * [IDamageApplier] / [IStatusEffectApplier] / [IWorldGateway.spawnParticle].
 * NO `org.bukkit.entity.AreaEffectCloud` vanilla entity — particles emit
 * via spec's [AreaEffectAction.SpawnParticle] action branch through
 * [IWorldGateway] so Astera's particle scheduling stays in control.
 *
 * # Why no AreaEffectCloud
 *
 * Vanilla `AreaEffectCloud` entities carry their own particle / duration
 * / radius parameters and emit their own potion effects on contact. Layering
 * that on top of Astera's [AreaEffectSpec] would:
 *   - double-emit particles (the vanilla cloud + our per-tick `SpawnParticle`),
 *   - bind us to vanilla potion effect ids when our domain uses
 *     [com.astarworks.astera.core.domain.status.StatusEffectId] strings, and
 *   - leak a lifetime out of `IScheduler` (vanilla cloud despawns on its
 *     own `duration` ticks, not the gateway's).
 * The cleaner contract: Astera owns the schedule, geometry, and effect
 * application; Bukkit is the particle / damage / potion sink.
 *
 * # Action dispatch
 *
 * Mirrors [com.astarworks.astera.combat.application.service.aoe.InMemoryAreaEffectGateway]
 * SPI contract exactly:
 *  - Lifecycle (`spawn` → `runRepeating` tick task + optional `runLater`
 *    despawn task → publish [AreaEffectSpawned] / [AreaEffectDespawned])
 *    is identical to InMemory.
 *  - The per-tick action is baked in (no `TickAction` SPI): the gateway
 *    `when`s on [AreaEffectAction] and routes:
 *    * [AreaEffectAction.ApplyStatusEffect] → [IStatusEffectApplier.apply]
 *      for every player in [AreaEffectSpec.shape] (status duration =
 *      `spec.tickIntervalTicks`, so each tick refreshes the effect for the
 *      next interval; an `ApplyStatusEffect` action on a 20-tick interval
 *      keeps the effect live continuously until the area expires).
 *    * [AreaEffectAction.DealDamage] → [IDamageApplier.apply] for every
 *      player in [AreaEffectSpec.shape] with the spec's profile.
 *    * [AreaEffectAction.SpawnParticle] → [IWorldGateway.spawnParticle] at
 *      [AreaEffectSpec.center] (purely visual; no target query needed).
 *
 * The combat plugin registers `IDamageApplier` and `IStatusEffectApplier`
 * at priority 100 in `AsteraCombatPlugin.onEnable` (Wave 5effects /
 * ADR-0109); this adapter just consumes the registry-resolved binding.
 *
 * # Threading
 *
 * `World.spawnParticle` (via [IWorldGateway]) and Bukkit player lookups
 * MUST run on the main thread. [IScheduler]'s `runLater` / `runRepeating`
 * callbacks fire on main per the [IScheduler] contract, so the per-tick
 * action is safe by construction. Off-main callers of [spawn] route
 * through `scheduler.runLater(Duration.ZERO)` to bounce to main — the
 * synchronous handle is generated eagerly so callers get a handle they
 * can [despawn] before the setup callback runs.
 *
 * # Backend id
 *
 * [BACKEND_ID] = `"paper"`. Handles created by [InMemoryAreaEffectGateway]
 * (id `"in-memory"`) cannot be despawned through this gateway — [despawn]
 * returns [AreaEffectError.UnknownHandle] for a backend-id mismatch.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCombatPlugin.onEnable` (overlays
 * `InMemoryAreaEffectGateway` priority 0). Collaborator chain (all
 * registry-resolved): `Server`, `IScheduler`, `IDomainEventBus`,
 * `INearbyTargetQuery` (Paper impl at p=100), `IDamageApplier`
 * (combat p=100), `IStatusEffectApplier` (combat p=100), `IWorldGateway`
 * (core Paper p=100).
 */
public class PaperAreaEffectGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val nearbyQuery: INearbyTargetQuery,
    private val damageApplier: IDamageApplier,
    private val statusApplier: IStatusEffectApplier,
    private val worldGateway: IWorldGateway,
) : IAreaEffectGateway {

    private data class Live(
        val spec: AreaEffectSpec,
        val tickTask: IScheduler.TaskHandle,
        val despawnTask: IScheduler.TaskHandle?,
    )

    private val live: MutableMap<AreaEffectHandle, Live> = linkedMapOf()

    override fun spawn(spec: AreaEffectSpec): Result<AreaEffectHandle, AreaEffectError> {
        // World load-state is read off [Server]. Paper's [Server.getWorld]
        // is thread-safe (it queries the loaded-world set); we can call it
        // off-main to fail fast on the unloaded case without bouncing first.
        if (server.getWorld(spec.world.value) == null) {
            return Result.failure(AreaEffectError.WorldNotLoaded(spec.world))
        }

        val handle = AreaEffectHandle(id = UUID.randomUUID(), backendId = BACKEND_ID)

        if (!server.isPrimaryThread) {
            // Off-main: defer registration to main. The handle is returned
            // eagerly so callers can [despawn] before the setup runs (the
            // [despawn] code path looks up [live] which is empty until the
            // bounce completes — returns UnknownHandle as the InMemory
            // gateway would for any pre-registration despawn).
            scheduler.runLater(Duration.ZERO) {
                completeSpawnOnMain(handle, spec)
            }
            return Result.success(handle)
        }

        completeSpawnOnMain(handle, spec)
        return Result.success(handle)
    }

    private fun completeSpawnOnMain(handle: AreaEffectHandle, spec: AreaEffectSpec) {
        val tickTask = scheduler.runRepeating(
            initialDelay = ticks(spec.tickIntervalTicks.toLong()),
            period = ticks(spec.tickIntervalTicks.toLong()),
        ) {
            dispatchTick(handle, spec)
        }

        val despawnTask: IScheduler.TaskHandle? = if (spec.durationTicks > 0) {
            scheduler.runLater(ticks(spec.durationTicks.toLong())) {
                despawnInternal(handle, AreaEffectDespawned.DespawnReason.ExpiredNaturally)
            }
        } else {
            null
        }

        live[handle] = Live(spec, tickTask, despawnTask)
        eventBus.publish(
            AreaEffectSpawned(
                handle = handle,
                world = spec.world,
                center = spec.center,
                shape = spec.shape,
                durationTicks = spec.durationTicks,
                tickIntervalTicks = spec.tickIntervalTicks,
                action = spec.effectPerTick,
                atTick = scheduler.currentTick(),
            ),
        )

        if (spec.durationTicks == 0) {
            // duration 0 = fire-once-and-despawn semantics. Mirror InMemory:
            // run the action immediately so subscribers see the spawn /
            // despawn pair with the action applied between them.
            dispatchTick(handle, spec)
            despawnInternal(handle, AreaEffectDespawned.DespawnReason.ExpiredNaturally)
        }
    }

    /**
     * Per-tick action. Resolves entities in [AreaEffectSpec.shape] (via
     * [INearbyTargetQuery] using the shape's bounding radius) and routes
     * the [AreaEffectAction] variant to the appropriate applier port.
     *
     * Shape filtering: [INearbyTargetQuery] is radius-based, so non-circle
     * shapes (Box, Ring) are over-approximated by their bounding radius and
     * the gateway does no secondary filter. This matches InMemory's no-
     * filter contract — fine-grained shape filtering belongs in a future
     * `IShapedNearbyQuery` port (Phase 2+); the gateway's job is to schedule
     * + dispatch, not to refine geometry.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun dispatchTick(handle: AreaEffectHandle, spec: AreaEffectSpec) {
        // [handle] is kept on the signature so the SPI matches InMemory's
        // [TickAction.tick] contract — future variants (e.g. logging per
        // handle) won't need an SPI change. Currently unused in dispatch.
        when (val action = spec.effectPerTick) {
            is AreaEffectAction.ApplyStatusEffect -> {
                val targets = nearbyQuery.findInRadius(
                    center = spec.center,
                    radius = boundingRadius(spec.shape),
                    world = spec.world,
                )
                for (target in targets) {
                    statusApplier.apply(
                        target = target,
                        effectId = action.effectId,
                        durationTicks = spec.tickIntervalTicks,
                    )
                }
            }
            is AreaEffectAction.DealDamage -> {
                val targets = nearbyQuery.findInRadius(
                    center = spec.center,
                    radius = boundingRadius(spec.shape),
                    world = spec.world,
                )
                for (target in targets) {
                    damageApplier.apply(target = target, profile = action.profile)
                }
            }
            is AreaEffectAction.SpawnParticle -> {
                // Purely visual — no target query. [IWorldGateway] handles
                // the unloaded-world / unknown-particle-key cases internally
                // (warn-logs and no-ops; see PaperWorldGateway:141-153).
                worldGateway.spawnParticle(
                    world = spec.world,
                    at = spec.center,
                    particle = action.particle,
                )
            }
        }
    }

    override fun despawn(handle: AreaEffectHandle): Result<Unit, AreaEffectError> {
        if (handle.backendId != BACKEND_ID) {
            return Result.failure(AreaEffectError.UnknownHandle(handle))
        }
        if (handle !in live) {
            return Result.failure(AreaEffectError.UnknownHandle(handle))
        }
        despawnInternal(handle, AreaEffectDespawned.DespawnReason.ExplicitDespawn)
        return Result.success(Unit)
    }

    private fun despawnInternal(
        handle: AreaEffectHandle,
        reason: AreaEffectDespawned.DespawnReason,
    ) {
        val entry = live.remove(handle) ?: return
        entry.tickTask.cancel()
        entry.despawnTask?.cancel()
        eventBus.publish(
            AreaEffectDespawned(
                handle = handle,
                reason = reason,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    override fun all(): Collection<AreaEffectHandle> = live.keys.toList()

    public companion object {
        public const val BACKEND_ID: String = "paper"

        /**
         * Conservative bounding radius for [INearbyTargetQuery]. The
         * radius-only query is an over-approximation for Box / Ring; the
         * gateway accepts that imprecision in Phase 1 (the shapes are
         * single-digit-meter sized in practice). A future shape-aware
         * query would replace this.
         */
        internal fun boundingRadius(shape: AreaEffectShape): Double = when (shape) {
            is AreaEffectShape.Circle -> shape.radius
            is AreaEffectShape.Box -> {
                // Half-diagonal of the AABB.
                val hx = shape.sizeX * 0.5
                val hy = shape.sizeY * 0.5
                val hz = shape.sizeZ * 0.5
                kotlin.math.sqrt(hx * hx + hy * hy + hz * hz)
            }
            is AreaEffectShape.Ring -> shape.outerRadius
        }
    }
}

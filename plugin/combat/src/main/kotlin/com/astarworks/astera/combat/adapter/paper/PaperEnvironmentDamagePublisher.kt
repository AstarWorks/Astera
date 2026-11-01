package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.aoe.EnvironmentDamageApplied
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory

/**
 * Combat-side Paper listener that publishes
 * [EnvironmentDamageApplied] for every vanilla-intrusion
 * [EntityDamageEvent] hitting a player (Wave 6a-N3 sub-C, ADR-0159).
 *
 * # Why this exists
 *
 * Wave 6a-6 (ADR-0153) introduced
 * [com.astarworks.astera.core.adapter.paper.event.BukkitEventAdapter] Role 2,
 * which cancels vanilla-intrusion `EntityDamageEvent`s on players and
 * re-routes the damage through
 * [com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway.damage]
 * so Astera HP stays canonical. That adapter however cannot publish the
 * combat-domain event [EnvironmentDamageApplied] — its type lives in
 * `combat.domain.event.aoe.*` and ADR-0022 sibling-isolation forbids
 * `plugin:core` from importing combat.*. This publisher closes the
 * ADR-0134 deferred-events allowlist entry for `combat.EnvironmentDamageApplied`
 * by living in `plugin:combat` where the import is legal.
 *
 * # Priority + cancellation contract
 *
 * Bukkit priority order runs **LOWEST → LOW → … → MONITOR**. Core's
 * BukkitEventAdapter cancels at [EventPriority.LOW]; this publisher runs
 * at [EventPriority.LOWEST] so it observes the event *before* Core
 * cancels it. The two handlers therefore see the same set of events on
 * the same tick: this publisher emits the combat-analytics event,
 * Core then cancels + re-routes through the health gateway.
 *
 * This handler is publish-only — it never mutates `event.isCancelled`,
 * `event.damage`, or any other event property. Cancellation is Core's
 * job. Setting `ignoreCancelled = false` is intentional: at LOWEST
 * priority no prior listener has had a chance to cancel, so the flag
 * is moot, but explicit `false` documents that this publisher does
 * not gate on cancellation state.
 *
 * # Cause classification
 *
 * Bukkit `EntityDamageEvent.DamageCause` is partitioned into two
 * disjoint sets, mirroring Core BukkitEventAdapter Role 2's split so
 * the two handlers stay coherent across causes:
 *
 *  - **Combat-pipeline causes** (`ENTITY_ATTACK`, `ENTITY_SWEEP_ATTACK`,
 *    `PROJECTILE`, `MAGIC`, `ENTITY_EXPLOSION`): Astera-mediated damage
 *    already produced its own [EnvironmentDamageApplied]-shaped event
 *    via `ApplyDamageUseCase`'s outcome publish chain. Re-publishing
 *    here would double-count combat analytics. Skip.
 *  - **Vanilla-intrusion causes** (FALL / LAVA / FIRE / DROWNING / VOID
 *    / SUFFOCATION / …): Astera did not originate the damage; publish
 *    [EnvironmentDamageApplied] with the appropriate
 *    [DamageSource.Environment] sub-variant so analytics + the future
 *    achievement system + shield-consumption listeners react.
 *
 * # Source mapping
 *
 * The Bukkit cause is mapped to one of the named
 * [DamageSource.Environment] variants:
 *
 *  - `FALL`, `FLY_INTO_WALL` → [DamageSource.Environment.Fall] with
 *    `distance = (event.entity as? Player)?.fallDistance?.toDouble() ?: 0.0`.
 *    Wall-impact damage has no separate distance field in Bukkit; using
 *    the entity's accumulated fall distance is the best available proxy.
 *  - `DROWNING` → [DamageSource.Environment.Drown] with
 *    `remainingAir = player.remainingAir.coerceAtLeast(0)`.
 *  - `FIRE`, `FIRE_TICK`, `LAVA`, `HOT_FLOOR`, `CAMPFIRE` →
 *    [DamageSource.Environment.Fire] with
 *    `ticksOnFire = player.fireTicks.coerceAtLeast(0)`.
 *  - `LIGHTNING` → [DamageSource.Environment.Lightning].
 *  - `VOID` → [DamageSource.Environment.Void].
 *  - `SUFFOCATION` → [DamageSource.Environment.Suffocation].
 *  - Anything else routed through Role 2 (`CONTACT`, `STARVATION`,
 *    `FREEZE`, `BLOCK_EXPLOSION`, `MELTING`, `POISON`, `WITHER`,
 *    `DRAGON_BREATH`, `FALLING_BLOCK`, `CRAMMING`, `KILL`,
 *    `WORLD_BORDER`, `SUICIDE`, `THORNS`, `DRYOUT`, `SONIC_BOOM`,
 *    `CUSTOM`, and any forward-compatible unclassified cause) →
 *    [DamageSource.Environment.Generic].
 *
 * # Threading + exception safety
 *
 * Bukkit dispatches synchronously on the main thread; this listener
 * runs on main and touches no async state. Every `eventBus.publish`
 * is wrapped in a try/catch that logs WARN and swallows so a faulty
 * subscriber cannot propagate back into Bukkit's dispatch loop (which
 * would either disable the plugin or trigger re-entrant event
 * recursion).
 *
 * # Registration
 *
 * Construct in `AsteraCombatPlugin.onEnable` and call [start] with the
 * plugin instance. Bukkit's `HandlerList.unregisterAll(plugin)` during
 * normal plugin teardown unregisters the listener — no manual handle
 * needed.
 *
 * # Wave provenance
 *
 *  - ADR-0159 — this wave (Wave 6a-N3 sub-C), introduces the publisher.
 *  - ADR-0153 — Wave 6a-6, Core BukkitEventAdapter Role 2 sibling that
 *    handles the cancellation half.
 *  - ADR-0134 — Wave 4o deferred-events allowlist; this publisher is the
 *    closure for the `combat.EnvironmentDamageApplied` row.
 *  - ADR-0088 — Wave 5m, sealed `DamageSource.Environment` hierarchy
 *    consumed by `EnvironmentDamageApplied.source`.
 *  - ADR-0022 — multi-plugin layout + sibling isolation, the reason this
 *    publisher lives in `plugin:combat` rather than `plugin:core`.
 */
public class PaperEnvironmentDamagePublisher(
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) : Listener {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Register `@EventHandler`s on the provided plugin instance. */
    public fun start(plugin: JavaPlugin, listeners: OwnedListeners) {
        listeners.register(this)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public fun onEntityDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        when (event.cause) {
            // Combat-pipeline causes: ApplyDamageUseCase owns the analytics
            // publish for these. Skip so EnvironmentDamageApplied is not
            // double-emitted.
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
            EntityDamageEvent.DamageCause.PROJECTILE,
            EntityDamageEvent.DamageCause.MAGIC,
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
            -> return

            else -> publishEnvironmentDamage(player, event)
        }
    }

    private fun publishEnvironmentDamage(player: Player, event: EntityDamageEvent) {
        val source: DamageSource.Environment = mapBukkitCauseToEnvironment(player, event.cause)
        val playerId = PlayerId(player.uniqueId)
        // `EntityDamageEvent.finalDamage` is post-mitigation; clamp at 0 so
        // the DomainEvent invariant `finalDamage >= 0.0` cannot be tripped
        // by a future Bukkit edge case (negative finalDamage on absorbed
        // hits has been observed in some Paper builds).
        val amount = event.finalDamage.coerceAtLeast(0.0)
        try {
            eventBus.publish(
                EnvironmentDamageApplied(
                    target = playerId,
                    source = source,
                    finalDamage = amount,
                    atTick = scheduler.currentTick(),
                ),
            )
        } catch (ex: RuntimeException) {
            log.warn(
                "PaperEnvironmentDamagePublisher: publish failed for {} cause={} amount={}: {}",
                playerId,
                event.cause,
                amount,
                ex.message,
            )
        }
    }

    /**
     * Map Bukkit's `DamageCause` to one of the named
     * [DamageSource.Environment] variants. Unknown / future causes fall
     * through to [DamageSource.Environment.Generic] so a new Bukkit
     * variant does not break the publish path.
     */
    private fun mapBukkitCauseToEnvironment(
        player: Player,
        cause: EntityDamageEvent.DamageCause,
    ): DamageSource.Environment = when (cause) {
        EntityDamageEvent.DamageCause.FALL,
        EntityDamageEvent.DamageCause.FLY_INTO_WALL,
        -> DamageSource.Environment.Fall(
            distance = player.fallDistance.toDouble().coerceAtLeast(0.0),
        )

        EntityDamageEvent.DamageCause.DROWNING,
        -> DamageSource.Environment.Drown(
            remainingAir = player.remainingAir.coerceAtLeast(0),
        )

        EntityDamageEvent.DamageCause.FIRE,
        EntityDamageEvent.DamageCause.FIRE_TICK,
        EntityDamageEvent.DamageCause.LAVA,
        EntityDamageEvent.DamageCause.HOT_FLOOR,
        EntityDamageEvent.DamageCause.CAMPFIRE,
        -> DamageSource.Environment.Fire(
            ticksOnFire = player.fireTicks.coerceAtLeast(0),
        )

        EntityDamageEvent.DamageCause.LIGHTNING -> DamageSource.Environment.Lightning
        EntityDamageEvent.DamageCause.VOID -> DamageSource.Environment.Void
        EntityDamageEvent.DamageCause.SUFFOCATION -> DamageSource.Environment.Suffocation

        else -> DamageSource.Environment.Generic
    }
}

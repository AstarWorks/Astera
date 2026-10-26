package com.astarworks.astera.combat.adapter.paper.aoe

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.combat.application.port.outbound.aoe.ILightningGateway
import com.astarworks.astera.combat.domain.aoe.LightningError
import com.astarworks.astera.combat.domain.event.aoe.LightningStrike
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Server
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [ILightningGateway] (Wave 6d, ADR-0161) — closes the
 * Wave 5m (ADR-0088) AOE-port gateway-boundary contract.
 *
 * # Visual-only vs lethal mode
 *
 * The [damageEntities] flag controls which Bukkit primitive is invoked:
 *  - `true`  → `World.strikeLightning(Location)` — vanilla lightning bolt
 *    that deals ~5 hp of `EntityDamageEvent.DamageCause.LIGHTNING` damage
 *    to entities at the strike point. Channels through the Wave 6a
 *    `IDamageGateway` hijack path.
 *  - `false` → `World.strikeLightningEffect(Location)` — visual + audio
 *    only, no entity damage. Used by bosses and weather systems that want
 *    pure FX.
 *
 * Matches the port semantics exactly; do not collapse the two cases.
 *
 * # Domain event
 *
 * Publishes [LightningStrike] on success, mirroring
 * [com.astarworks.astera.combat.application.service.aoe.InMemoryLightningGateway].
 * Listeners (analytics, sound-cue overlays, betting-match commentators)
 * fire regardless of Paper / in-memory backend — preserves the contract.
 *
 * # Threading
 *
 * Bukkit `World.strikeLightning` + `strikeLightningEffect` MUST be called
 * on the main thread. The port KDoc does not declare an off-main error
 * variant (only `WorldNotLoaded` + `NoImplementationBound`), and adding one
 * would be a port API change beyond Wave 6d scope. Mirror the
 * `IFireSpreadController` posture: log a WARN on off-main and best-effort
 * delegate via [IScheduler] to next-tick main-thread invocation. Production
 * callers (combat use-cases run sync from event handlers) hit the main path.
 *
 * # ADR-0149 §C
 *
 * N/A — entity damage flows through Bukkit's vanilla
 * `EntityDamageEvent.DamageCause.LIGHTNING` and is intercepted by Wave 6a
 * `PaperDamageGateway` upstream of this adapter. This gateway does not
 * touch player damage / health directly.
 *
 * # Registration
 *
 * Registered at priority 100 in `AsteraCombatPlugin.onEnable` (overlays
 * `InMemoryLightningGateway` priority 0).
 */
public class PaperLightningGateway(
    private val server: Server,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) : ILightningGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun strike(
        world: WorldId,
        position: Vec3,
        damageEntities: Boolean,
    ): Result<Unit, LightningError> {
        val bukkitWorld = server.getWorld(world.value)
            ?: return Result.failure(LightningError.WorldNotLoaded(world))

        val location = Location(bukkitWorld, position.x, position.y, position.z)

        if (!org.bukkit.Bukkit.isOwnedByCurrentRegion(location)) {
            log.warn(
                "PaperLightningGateway.strike invoked off-main (world={}, pos={}); " +
                    "deferring to next main tick. Callers should round-trip via IScheduler.",
                world, position,
            )
            scheduler.run(regionScope(world, position), kotlin.time.Duration.ZERO) {
                fireAndPublish(bukkitWorld, location, world, position, damageEntities)
            }
            return Result.success(Unit)
        }

        fireAndPublish(bukkitWorld, location, world, position, damageEntities)
        return Result.success(Unit)
    }

    private fun fireAndPublish(
        bukkitWorld: org.bukkit.World,
        location: Location,
        world: WorldId,
        position: Vec3,
        damageEntities: Boolean,
    ) {
        if (damageEntities) {
            bukkitWorld.strikeLightning(location)
        } else {
            bukkitWorld.strikeLightningEffect(location)
        }
        eventBus.publish(
            LightningStrike(
                world = world,
                position = position,
                damageEntities = damageEntities,
                atTick = scheduler.currentTick(),
            ),
        )
    }
}

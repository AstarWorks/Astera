package com.astarworks.astera.stats.scenario.fake

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.application.port.outbound.IProjectileHandle
import com.astarworks.astera.weapons.application.port.outbound.IProjectileService
import com.astarworks.astera.weapons.domain.projectile.ProjectileId
import com.astarworks.astera.weapons.domain.projectile.ProjectileSpec

/**
 * Test fake for [IProjectileService] with deterministic projectile behaviour.
 *
 * Wave _infra-1 / ADR-0059. Does NOT simulate ballistic arcs — every spawn is
 * recorded as a [Flight] and stays "alive" until [completeAll] is called,
 * which marks every in-flight projectile dead and (optionally) publishes a
 * synthetic on-hit signal via the bus passed at construction. Scenarios use
 * the simpler `IMcPlayer.fires(weapon).at(target)` / `kills(target, with)`
 * DSL when they don't care about projectile-level fidelity; this fake exists
 * for tests that DO want to inspect spawn order, owner attribution, or
 * cancellation behaviour without standing up the Paper tick loop.
 *
 * Thread-agnostic — tests don't simulate the Paper main-thread requirement
 * called out by the production [com.astarworks.astera.weapons.adapter.PaperProjectileService].
 */
public class FakeProjectileService(
    private val eventBus: IDomainEventBus? = null,
) : IProjectileService {

    /** A single recorded spawn. */
    public data class Flight(
        val id: ProjectileId,
        val spec: ProjectileSpec,
        val origin: Vec3,
        val direction: Vec3,
        val owner: PlayerId,
        var alive: Boolean = true,
    )

    public val spawned: MutableList<Flight> = mutableListOf()

    override fun spawn(
        spec: ProjectileSpec,
        origin: Vec3,
        direction: Vec3,
        owner: PlayerId,
    ): IProjectileHandle {
        val flight = Flight(
            id = ProjectileId.random(),
            spec = spec,
            origin = origin,
            direction = direction,
            owner = owner,
        )
        spawned += flight
        return Handle(flight)
    }

    /**
     * Mark every in-flight projectile dead. Used by scenarios to model "after
     * the projectile tick loop converges, every shot resolved". The bus
     * argument is intentionally not consumed here — scenarios that need a
     * particular on-hit event publish it themselves after `completeAll()` so
     * the target [PlayerId] is explicit.
     */
    public fun completeAll() {
        spawned.forEach { it.alive = false }
    }

    /** Convenience: alive flights that still need to resolve. */
    public fun aliveFlights(): List<Flight> = spawned.filter { it.alive }

    private class Handle(private val flight: Flight) : IProjectileHandle {
        override val id: ProjectileId get() = flight.id
        override val isAlive: Boolean get() = flight.alive
        override fun cancel() {
            flight.alive = false
        }
    }
}

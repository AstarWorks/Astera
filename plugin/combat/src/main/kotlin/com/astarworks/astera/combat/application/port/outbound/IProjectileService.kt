package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec

/**
 * Outbound port: spawn a projectile and let the adapter own its runtime.
 *
 * The application never sees the in-flight projectile — only an
 * [IProjectileHandle] for cancellation and liveness checks. The adapter
 * (Phase 2 mid late: `PaperProjectileService`) runs the tick loop,
 * collision tests, and effect dispatch.
 *
 * Per ADR-0015, Astera builds this rather than depending on WeaponMechanics.
 */
public interface IProjectileService {
    /**
     * Spawn a projectile.
     *
     * @param spec immutable definition (motion / hit / lifetime / onHit / visual)
     * @param origin world position to spawn at
     * @param direction normalized direction vector (caller normalizes; the
     *   adapter trusts the input)
     * @param owner the player who fired this projectile; used for damage
     *   attribution and friendly-fire checks
     * @return handle for cancellation / liveness
     */
    public fun spawn(
        spec: ProjectileSpec,
        origin: Vec3,
        direction: Vec3,
        owner: PlayerId,
    ): IProjectileHandle
}

/** Handle to a live projectile. */
public interface IProjectileHandle {
    public val id: ProjectileId
    public val isAlive: Boolean

    /** Current world position while alive, `null` when the host cannot report it (or the projectile is gone). */
    public val position: com.astarworks.astera.core.domain.geometry.Vec3?
        get() = null

    public fun cancel()
}

package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.weapons.application.port.outbound.IProjectileHandle
import com.astarworks.astera.weapons.application.port.outbound.IProjectileService
import com.astarworks.astera.weapons.domain.projectile.ProjectileId
import com.astarworks.astera.weapons.domain.projectile.ProjectileSpec

/**
 * Test fixture for [IProjectileService] — records every spawn for assertion.
 *
 * Each call appends to [spawned] with the spec, origin, direction, and owner;
 * the returned handle is a no-op [FakeProjectileHandle] that reports
 * `isAlive = true` until [FakeProjectileHandle.cancel] flips it.
 */
public class FakeProjectileService : IProjectileService {

    public data class Spawn(
        val spec: ProjectileSpec,
        val origin: Vec3,
        val direction: Vec3,
        val owner: PlayerId,
        val handle: FakeProjectileHandle,
    )

    public val spawned: MutableList<Spawn> = mutableListOf()

    override fun spawn(
        spec: ProjectileSpec,
        origin: Vec3,
        direction: Vec3,
        owner: PlayerId,
    ): IProjectileHandle {
        val handle = FakeProjectileHandle()
        spawned += Spawn(spec, origin, direction, owner, handle)
        return handle
    }
}

/** Test handle: alive until [cancel]; id is a stable random UUID per instance. */
public class FakeProjectileHandle(
    override val id: ProjectileId = ProjectileId.random(),
) : IProjectileHandle {

    private var alive: Boolean = true

    override val isAlive: Boolean get() = alive

    override fun cancel() {
        alive = false
    }
}

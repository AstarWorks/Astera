package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.lobby.TrainingDummySpot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: the visible bodies of the lobby training dummies (F3-11, ADR-0200). A body is only a
 * hitbox and a name tag; HP, DPS and regeneration live in
 * [com.astarworks.astera.combat.application.service.training.TrainingDummy]. The body's id is its entity
 * id, so targeting finds it like any other mob.
 */
public interface ITrainingDummyShells {
    /** Spawns a body for [spot] at [at]; `null` while [world] is not loaded. */
    public fun spawn(world: WorldId, spot: TrainingDummySpot, at: Vec3): PlayerId?

    /** True while the body with [id] still exists in the world. */
    public fun isAlive(id: PlayerId): Boolean

    /** Feet position of the body, or `null` when it is gone. */
    public fun positionOf(id: PlayerId): Vec3?

    /** Puts the body at [at] with zero velocity (moving targets, and undoing knockback). */
    public fun moveTo(id: PlayerId, at: Vec3)

    /** Replaces the floating name / HP / DPS text above the body ([lines] are MiniMessage-formatted). */
    public fun setLabel(id: PlayerId, lines: List<String>)

    public fun remove(id: PlayerId)

    /** Removes every body this gateway spawned (plugin disable). */
    public fun removeAll()
}

/**
 * Players that must not take damage from other players: inside the lobby training range (F3-11).
 * Consulted by `ApplyDamageUseCase` and by the nearby-target query so skills do not even select them.
 */
public fun interface IDamageProtection {
    public fun isProtected(target: PlayerId): Boolean
}

package com.astarworks.astera.core.domain.animation.player

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Where a player is sitting (Wave 5animation, ADR-0113).
 *
 * Sealed so the renderer's `when` is exhaustive — Wave 6's
 * `PaperPlayerAnimationGateway` switches on the variant to choose between
 * `Player.setVehicle(armorStand)` (Vehicle), a synthesised invisible
 * armor-stand pinned at the supplied position (Position), or
 * `Player.leaveVehicle()` (None).
 *
 * # Why sealed (not enum)
 *
 * The two non-trivial variants carry payload (entity handle vs world
 * position) — an enum can't hold per-variant data. The sealed shape mirrors
 * [com.astarworks.astera.core.domain.world.TeleportReason] / Wave 5world-events.
 */
public sealed interface SitTarget {

    /**
     * Sit the player on an existing entity (vehicle, chair-mob, mount).
     * The Paper adapter mounts via `entity.addPassenger(player)`.
     *
     * @property entity vendor-neutral handle to the carrier entity.
     */
    public data class Vehicle(public val entity: EntityHandle) : SitTarget

    /**
     * Sit the player at a fixed world position. The Paper adapter
     * synthesises an invisible armor-stand at [position] and seats the
     * player on it.
     *
     * @property position world-space coordinates (block-relative offset
     *   handled by the adapter).
     */
    public data class Position(public val position: Vec3) : SitTarget

    /**
     * Stand the player up (dismount any current sit target). The Paper
     * adapter calls `player.leaveVehicle()` and clears any synthesised
     * armor-stand the gateway created for [Position].
     */
    public data object None : SitTarget
}

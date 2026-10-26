package com.astarworks.astera.core.domain.player

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Vendor-neutral snapshot of where a player is and where they are looking.
 *
 * ADR-0174: skill casts (projectile launch direction, forward-ray / cone
 * targeters) need a facing source that works identically under Paper and the
 * headless simulator.
 *
 * @property world world the player is in.
 * @property position feet position (Bukkit `Location`).
 * @property eyePosition eye position — where a projectile leaves the caster.
 * @property direction **unit** look vector (never zero; adapters fall back to
 *   `+Z`-forward style defaults rather than emit a zero vector).
 */
public data class PlayerPose(
    public val world: WorldId,
    public val position: Vec3,
    public val eyePosition: Vec3,
    public val direction: Vec3,
) {
    public companion object {
        /** Standing eye height used by fakes and the simulator. */
        public const val STANDING_EYE_HEIGHT: Double = 1.62

        /** Pose with eye derived from feet at [STANDING_EYE_HEIGHT]; [direction] is normalized. */
        public fun standing(world: WorldId, position: Vec3, direction: Vec3): PlayerPose = PlayerPose(
            world = world,
            position = position,
            eyePosition = position + Vec3(0.0, STANDING_EYE_HEIGHT, 0.0),
            direction = direction.normalize().takeIf { it.lengthSquared() > 0.0 } ?: Vec3(1.0, 0.0, 0.0),
        )
    }
}

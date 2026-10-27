package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Outbound port: a per-player hologram guide (ADR-0295). One arrow floats a couple of blocks ahead of [point]'s
 * viewer at eye height, turns toward the target as the viewer looks around and reads `<label> 23m`. Only the viewer
 * sees it; nobody else gets the entity. At most one guide per viewer.
 *
 * Callers (the match objective kit, later the lobby tutorial) only say *where* the viewer should go; the host owns the
 * entity, follows the viewer and measures the distance itself. A guide that is not refreshed within its time-to-live
 * disappears on its own, so a caller that forgets [hide] cannot leak an entity.
 */
public interface IHologramGuide {
    /**
     * Points [viewer]'s guide at [target] (creates it on the first call, retargets afterwards).
     *
     * @param label rendered MiniMessage line in the viewer's language, without arrow or distance.
     * @param ttlTicks the guide vanishes this many ticks after the last call.
     */
    public fun point(viewer: PlayerId, target: Vec3, label: String, ttlTicks: Int = DEFAULT_TTL_TICKS)

    /** Removes [viewer]'s guide. Idempotent. */
    public fun hide(viewer: PlayerId)

    public companion object {
        /** A refresh once a second keeps the guide; two missed refreshes remove it. */
        public const val DEFAULT_TTL_TICKS: Int = 50

        private val ARROWS: List<String> = listOf("↑", "↖", "←", "↙", "↓", "↘", "→", "↗")

        /** Eight-way arrow for a target offset ([dx], [dz]) seen from a viewer facing ([fx], [fz]) on the ground plane. */
        public fun arrowFor(fx: Double, fz: Double, dx: Double, dz: Double): String {
            if (hypot(fx, fz) < 1e-6 || hypot(dx, dz) < 1e-6) return ARROWS[0]
            var rel = atan2(dx, dz) - atan2(fx, fz)
            while (rel > PI) rel -= 2 * PI
            while (rel <= -PI) rel += 2 * PI
            return ARROWS[Math.floorMod((rel / (PI / 4)).roundToInt(), 8)]
        }
    }
}

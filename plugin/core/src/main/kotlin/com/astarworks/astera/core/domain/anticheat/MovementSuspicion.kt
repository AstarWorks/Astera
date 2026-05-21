package com.astarworks.astera.core.domain.anticheat

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * A heuristic-grade movement anomaly that warrants attention but is not
 * conclusive enough to reject outright. Surfaced via
 * [com.astarworks.astera.core.application.port.outbound.anticheat.IMovementValidator]
 * and forwarded to external anti-cheat bridges through
 * [AnticheatSuspicion.Movement] (ADR-0083).
 *
 * Intentionally narrow — only the variants Astera's own light validator can
 * emit. Richer signals (Killaura / Reach / FastBow) live in the external
 * anti-cheat plugin domain and surface through
 * [AnticheatSuspicion.CustomFlag].
 */
public sealed interface MovementSuspicion {

    /**
     * Player moved faster than [max] m/tick. Sprint, soul-speed, slowness, and
     * status modifiers are folded into [max] before comparison.
     */
    public data class HighSpeed(val measured: Double, val max: Double) : MovementSuspicion

    /**
     * Unusual vertical delta — for instance a 6-block upward jump without a
     * legitimate explanation (jump-boost, riptide, levitation). Positive
     * [delta] = upward, negative = downward.
     */
    public data class VerticalAnomaly(val delta: Double) : MovementSuspicion

    /**
     * Position changed across a region that should be impassable (block clip,
     * world-edge clip, claim-boundary clip). The pre/post coordinates are
     * retained so the upstream anti-cheat can replay the trajectory.
     */
    public data class WallClipping(val from: Vec3, val to: Vec3) : MovementSuspicion
}

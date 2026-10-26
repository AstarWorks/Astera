package com.astarworks.astera.core.application.service.anticheat

import com.astarworks.astera.core.application.port.outbound.anticheat.IMovementValidator
import com.astarworks.astera.core.domain.anticheat.MovementRejection
import com.astarworks.astera.core.domain.anticheat.MovementSuspicion
import com.astarworks.astera.core.domain.anticheat.MovementValidation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Light, hard-cap [IMovementValidator] suitable for the default Astera build.
 *
 * Three checks per validate-call, all O(1):
 *
 * 1. **Teleport** — Euclidean distance per tick exceeds [teleportCutoff].
 *    `Reject(Teleport)` immediately. The cap is intentionally generous
 *    (10 blocks/tick = 200 BPS) so it only catches the truly impossible.
 * 2. **HighSpeed** — distance per tick exceeds [highSpeedThreshold] but is
 *    below [teleportCutoff]. `Suspicious(HighSpeed)`.
 * 3. **VerticalAnomaly** — vertical delta exceeds [verticalThreshold].
 *    `Suspicious(VerticalAnomaly)`.
 *
 * The validator does **not** probe terrain — wall-clipping requires a
 * spatial-index port that Wave 5n's `ISpatialIndex` will provide; until
 * then the wall-clip path is left to an external anti-cheat plugin.
 *
 * Thresholds are exposed as constructor parameters so tests / live-config
 * editors (Wave 5t) can tune them per-server without recompilation.
 */
public class DefaultMovementValidator(
    private val teleportCutoff: Double = TELEPORT_CUTOFF_DEFAULT,
    private val highSpeedThreshold: Double = HIGH_SPEED_DEFAULT,
    private val verticalThreshold: Double = VERTICAL_DEFAULT,
) : IMovementValidator {

    override fun validate(
        playerId: PlayerId,
        from: Vec3,
        to: Vec3,
        deltaTicks: Int,
    ): MovementValidation {
        val ticks = deltaTicks.coerceAtLeast(1)
        val delta = to - from
        val horizontalSq = delta.x * delta.x + delta.z * delta.z
        val verticalAbs = kotlin.math.abs(delta.y)
        val horizontal = kotlin.math.sqrt(horizontalSq)
        val perTick = horizontal / ticks

        if (perTick >= teleportCutoff) {
            return MovementValidation.Reject(
                MovementRejection.Teleport(distance = horizontal),
            )
        }
        if (perTick >= highSpeedThreshold) {
            return MovementValidation.Suspicious(
                MovementSuspicion.HighSpeed(measured = perTick, max = highSpeedThreshold),
            )
        }
        if (verticalAbs / ticks >= verticalThreshold) {
            return MovementValidation.Suspicious(
                MovementSuspicion.VerticalAnomaly(delta = delta.y),
            )
        }
        return MovementValidation.Ok
    }

    public companion object {
        /** Default hard-cap for teleport detection: 10 blocks per tick. */
        public const val TELEPORT_CUTOFF_DEFAULT: Double = 10.0

        /** Default soft-cap for high-speed: 1 block per tick (~20 BPS). */
        public const val HIGH_SPEED_DEFAULT: Double = 1.0

        /** Default soft-cap for vertical anomaly: 3 blocks per tick. */
        public const val VERTICAL_DEFAULT: Double = 3.0
    }
}

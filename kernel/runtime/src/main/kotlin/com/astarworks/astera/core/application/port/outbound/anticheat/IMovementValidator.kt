package com.astarworks.astera.core.application.port.outbound.anticheat

import com.astarworks.astera.core.domain.anticheat.MovementValidation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port — light, in-process movement validation.
 *
 * Called from the Paper move-listener with the previous and new positions
 * plus the tick delta. Implementations are expected to be O(1) per call;
 * any heavy heuristic (BPS clustering, terrain probing) belongs on the
 * external anti-cheat plugin side accessed through
 * [IExternalAnticheatBridge].
 *
 * Astera's own implementation is intentionally narrow: hard-cap movement
 * deltas, basic vertical-anomaly detection, optional spatial-index wall
 * probing. The richer pipeline (network packet inspection, Killaura,
 * Reach) is delegated.
 */
public interface IMovementValidator {

    public fun validate(
        playerId: PlayerId,
        from: Vec3,
        to: Vec3,
        deltaTicks: Int,
    ): MovementValidation
}

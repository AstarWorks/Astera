package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.pin.PlayerPin
import com.astarworks.astera.match.domain.pin.PlayerPinId

/**
 * Outbound port: per-match player pin tracking (Wave 5e; RTM
 * `ConfiguredPlayerPin.kt`).
 *
 * Worker ι provides `InMemoryPlayerPinService`; persistence is not
 * required (pins are match-scoped and expire by [PlayerPin.lifetimeTicks]).
 * The service publishes `PlayerPinPlaced` / `PlayerPinExpired` events for
 * Wave 6a's Paper-side renderer.
 */
public interface IPlayerPinService {

    /** Register [pin] and emit `PlayerPinPlaced`. */
    public fun place(pin: PlayerPin)

    /** Force-expire [pinId] and emit `PlayerPinExpired`. */
    public fun expire(pinId: PlayerPinId)

    /** All currently-live pins in [matchId]. */
    public fun currentPins(matchId: MatchId): List<PlayerPin>

    /**
     * Advance the lifetime clock by [elapsedTicks]. Pins whose remaining
     * lifetime drops to ≤ 0 are expired (emit `PlayerPinExpired`). Called
     * by the unified scheduler (R0.h) every tick batch.
     */
    public fun tick(matchId: MatchId, elapsedTicks: Long)
}

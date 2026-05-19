package com.astarworks.astera.match.domain.pin

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID

@JvmInline
public value class PlayerPinId(public val value: UUID) {
    public companion object {
        public fun random(): PlayerPinId = PlayerPinId(UUID.randomUUID())
    }
}

/**
 * Team-comms map marker placed by a player (Wave 5e; RTM
 * `ConfiguredPlayerPin.kt`).
 *
 * Three sizes ([PinSize]) map to render scale / max range / lifetime
 * differently — worker ι defines the per-size defaults via
 * `IPlayerPinService`. Paper-side rendering (DisplayEntity / boss-bar
 * overlay) deferred to Wave 6a.
 */
public sealed interface PinSize {
    public data object Small : PinSize
    public data object Medium : PinSize
    public data object Large : PinSize
}

public data class PlayerPin(
    val id: PlayerPinId,
    val placerId: PlayerId,
    val matchId: MatchId,
    val position: Vec3,
    val size: PinSize,
    val lifetimeTicks: Long,
) {
    init {
        require(lifetimeTicks >= 1) { "lifetimeTicks must be >= 1: $lifetimeTicks" }
    }
}

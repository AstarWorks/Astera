package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.cinematic.CameraHandle
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5cinematic, ADR-0110): a previously-locked camera
 * was released (player regained control).
 *
 * Fired by [com.astarworks.astera.core.application.port.outbound.cinematic.ICameraGateway.releaseCamera]
 * and at the end of every `freeze(player, durationTicks)` window. Subscribers
 * (HUD, tutorial state machine) use this to restore normal input handlers.
 */
public data class CameraReleased(
    public val playerId: PlayerId,
    public val handle: CameraHandle,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}

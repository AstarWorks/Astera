package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Player sleep state changed (Wave 5animation, ADR-0113).
 *
 * Published by [com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway]
 * on `setSleeping`. Subscribers:
 *  - World time gateway (Wave 5n) — collective sleep skips night.
 *  - Cinematic system (Wave 5cinematic) — sleep can trigger a transition.
 *  - Anti-cheat (Wave 5q) — sleep-walk detection.
 *
 * @property playerId the player who changed state.
 * @property world world the player is in.
 * @property position bed position (when sleeping) or stand-up position (when not).
 * @property sleeping new state.
 * @property atTick server tick at change time.
 */
public data class PlayerSleepChanged(
    public val playerId: PlayerId,
    public val world: WorldId,
    public val position: Vec3,
    public val sleeping: Boolean,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}

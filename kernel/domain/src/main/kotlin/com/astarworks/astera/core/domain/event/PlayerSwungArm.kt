package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.animation.player.PlayerHand
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player swung their arm (left-click / item-use swing animation).
 *
 * Published by [com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway]
 * on every `swingArm` call (Wave 5animation, ADR-0113). Subscribers:
 *  - Skill trigger router (Wave 5a) — left-click skill bindings fire on
 *    this event (when the corresponding item is held).
 *  - Replay capture (Wave 5w) — records the swing for playback fidelity.
 *  - Combo system (Wave 5combat-depth) — accumulates swing events for
 *    finisher chain detection.
 *
 * @property playerId the player who swung.
 * @property hand which hand performed the swing.
 * @property atTick server tick at swing time.
 */
public data class PlayerSwungArm(
    public val playerId: PlayerId,
    public val hand: PlayerHand,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}

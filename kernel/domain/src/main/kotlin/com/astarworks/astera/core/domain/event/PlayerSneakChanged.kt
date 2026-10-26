package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player sneak state changed (Wave 5animation, ADR-0113).
 *
 * Published by [com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway]
 * on `setSneaking`. Subscribers:
 *  - Skill trigger router (Wave 5a) — sneak-modifier skill bindings.
 *  - Region permission (Wave 5n) — sneak-to-open chest semantics.
 *  - Anti-cheat (Wave 5q) — abnormal sneak-toggle rate detection.
 *
 * @property playerId the player who changed state.
 * @property sneaking new state (`true` = sneaking, `false` = standing).
 * @property atTick server tick at change time.
 */
public data class PlayerSneakChanged(
    public val playerId: PlayerId,
    public val sneaking: Boolean,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent

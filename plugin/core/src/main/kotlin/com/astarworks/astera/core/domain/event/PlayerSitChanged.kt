package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.animation.player.SitTarget
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Player sit state changed (Wave 5animation, ADR-0113).
 *
 * Published by [com.astarworks.astera.core.application.port.outbound.animation.IPlayerAnimationGateway]
 * on `setSitting`. Subscribers:
 *  - AFK detector (Wave 5o) — sitting on a chair past N ticks counts
 *    differently than standing AFK.
 *  - Replay capture (Wave 5w) — preserves cinematic-quality poses.
 *  - Match phase guard (Wave 5p) — disallow sit during active combat
 *    phase (e.g. norma intermission only).
 *
 * @property playerId the player who changed state.
 * @property target sealed sit-target variant (Vehicle / Position / None).
 *   None means the player stood up.
 * @property atTick server tick at change time.
 */
public data class PlayerSitChanged(
    public val playerId: PlayerId,
    public val target: SitTarget,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent

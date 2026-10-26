package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.animation.entity.MobAnimationKind
import com.astarworks.astera.core.domain.entity.EntityHandle
import java.time.Instant

/**
 * Mob animation triggered (Wave 5animation, ADR-0113).
 *
 * Published by [com.astarworks.astera.core.application.port.outbound.animation.IEntityAnimationGateway]
 * on every successful animation call. Subscribers:
 *  - Replay capture (Wave 5w) — records animation playback for high-
 *    fidelity replay.
 *  - PVE boss listener (Wave 5pve-boss) — `Death` animation triggers
 *    boss loot ceremony / phase ending.
 *  - Stats (Phase 4) — `Pickup` events for mob loot tracking.
 *
 * One event covers every animation variant via the [kind] discriminator
 * (sealed) — subscribers `when`-switch and drop the variants they don't
 * care about. Per-variant events would inflate the event class count and
 * force every subscriber to register N handlers.
 *
 * @property handle the entity that played the animation.
 * @property kind which animation was played + per-variant payload.
 * @property atTick server tick at animation time.
 */
public data class MobAnimationPlayed(
    public val handle: EntityHandle,
    public val kind: MobAnimationKind,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent

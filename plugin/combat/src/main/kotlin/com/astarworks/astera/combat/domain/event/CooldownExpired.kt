package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Fired when a previously-active cooldown entry expires.
 *
 * Published by `TickCooldownUseCase` (combat plugin) every server tick for
 * each `(playerId, cooldownKey)` pair returned by `ICooldownTracker.tick`.
 * Subscribers may react with HUD updates, sound cues, or skill-readiness
 * notifications without polling the tracker themselves.
 *
 * The opaque [cooldownKey] is carried as-is so subscribers see the same
 * sealed-variant shape (Weapon / Skill / Status / Custom) the tracker uses.
 * No decode-to-SkillId is performed here — that would lose information for
 * Weapon and Status variants, and a Skill-only event would force every
 * subscriber to filter by raw key prefix anyway.
 *
 * Lives in combat-domain (ADR-0022 sibling-isolation): the publisher
 * (TickCooldownUseCase) is combat-owned, and Stats / Economy / Items are
 * allowed to import combat-domain per the dependency matrix.
 *
 * ADR-0155 (Wave 6a-N2 combat polish — TickCooldownUseCase scheduler wiring
 * and CooldownExpired publish) records this event's lifecycle. ADR-0134
 * (publisher-must-exist) is satisfied at file-creation time since the
 * publisher ships in the same wave; no deferred-events allowlist entry
 * required.
 */
public data class CooldownExpired(
    public val playerId: PlayerId,
    public val cooldownKey: CooldownKey,
    public val expiredAtTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

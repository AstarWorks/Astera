package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `AssistAttributionUseCase` for each distinct non-killer
 * attacker whose damage on `victim` fell within the configured
 * `DamageHistoryWindow` ending at the kill. ADR-0063.
 *
 * `matchId` is nullable because non-match damage paths (lobby duels,
 * tutorial) can still produce assists; consumers must null-check.
 */
public data class AssistCredited(
    val player: PlayerId,
    val victim: PlayerId,
    val matchId: MatchId?,
    val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

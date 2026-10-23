package com.astarworks.astera.match.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A participant picked a generator's stockpile up. [total] is their match star count afterwards.
 * Stars are match-scoped: nothing outlives the match (not the wallet, ledger, database or inventory snapshot).
 */
public data class StarsCollected(
    val matchId: MatchId,
    val playerId: PlayerId,
    val amount: Int,
    val total: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

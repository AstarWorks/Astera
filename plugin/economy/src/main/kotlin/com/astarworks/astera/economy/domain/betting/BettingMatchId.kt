package com.astarworks.astera.economy.domain.betting

import java.util.UUID

/**
 * Identifier for a single [BettingMatch] (Wave 5bet / ADR-0103).
 *
 * Wraps a UUID — unlike [com.astarworks.astera.core.domain.match.MatchId] which
 * is a lower-kebab-case human-authored identifier shared between server
 * components, a betting match is a **per-acceptance**, server-minted record
 * that is never authored by humans. UUID is the right shape here.
 *
 * The underlying [com.astarworks.astera.core.domain.match.MatchId] of the
 * actual 1v1 PvP instance is carried separately on [BettingMatch.matchId].
 */
@JvmInline
public value class BettingMatchId(public val value: UUID) {
    override fun toString(): String = value.toString()

    public companion object {
        public fun mint(): BettingMatchId = BettingMatchId(UUID.randomUUID())
    }
}

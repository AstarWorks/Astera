package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.UUID

/**
 * Cross-plugin event (Wave 5bet / ADR-0103): a 1v1 betting match has reached
 * its terminal resolved state. The winner (if any) is known but the payout
 * has NOT yet been distributed — that is a separate
 * [BettingPayoutDistributed] event published once the escrow service has
 * credited the wallets.
 *
 * # When fired
 *
 * `ResolveBettingMatchOnMatchEndedListener` (economy plugin) after the
 * underlying `MatchEnded` event fires for a [MatchId] tracked by an Active
 * [com.astarworks.astera.economy.domain.betting.BettingMatch].
 *
 * # Subscribers
 *
 * - The economy plugin's `DistributeBettingPayoutOnResolvedListener` —
 *   reads the escrow, performs `releaseToWinner` / `refundBoth`, publishes
 *   [BettingPayoutDistributed].
 * - Wave 5e audit log: `betting.resolved` audit record.
 * - Wave 12g analytics: per-player W/L ratio, daily P/L distribution.
 *
 * # Why [winnerId] may be null
 *
 * Draw outcomes ([endReasonKind] == `draw` / `cancelled`) refund both
 * players — there is no winner to credit. Subscribers must handle the
 * `winnerId == null` case explicitly.
 *
 * # [endReasonKind]
 *
 * Snake-case discriminator mirroring
 * [com.astarworks.astera.economy.domain.betting.BettingEndReason]:
 * `match_outcome` / `draw` / `admin_cancelled` / `cancelled_with_reason`.
 */
public data class BettingMatchResolved(
    public val bettingMatchUuid: UUID,
    public val matchId: MatchId,
    public val challengerId: PlayerId,
    public val opponentId: PlayerId,
    public val winnerId: PlayerId?,
    public val endReasonKind: String,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(endReasonKind.isNotBlank()) { "BettingMatchResolved.endReasonKind must not be blank" }
        require(challengerId != opponentId) { "challenger and opponent must differ" }
        if (winnerId != null) {
            require(winnerId == challengerId || winnerId == opponentId) {
                "BettingMatchResolved.winnerId must be challenger or opponent"
            }
        }
    }
}

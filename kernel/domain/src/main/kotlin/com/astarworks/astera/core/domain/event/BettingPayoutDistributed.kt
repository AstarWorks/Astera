package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.UUID

/**
 * Cross-plugin event (Wave 5bet / ADR-0103): the escrow for a resolved
 * betting match has been released. Either the winner has been credited the
 * pot, OR both sides have been refunded their stake on a draw / cancel /
 * cleanup.
 *
 * Always follows a [BettingMatchResolved] for the same `bettingMatchUuid`.
 *
 * # Subscribers
 *
 * - Wave 5e audit log: `betting.paid_out` audit record (the SSOT for
 *   compliance — fund flow visibility).
 * - Wave 5flex (ADR-0102) Power Index: invalidate cache for crediting +
 *   debiting players.
 * - Wave 12g analytics: per-policy house-cut accumulation; payout
 *   distribution histogram.
 *
 * # Payout semantics
 *
 * - [winnerPayout] is `floor(2 × stake × policy.payoutSplit)` when [outcomeKind]
 *   == `winner_paid`; `0` when `refunded_both`. The Phase 1 default
 *   payoutSplit is `1.0` (winner all) — the field exists to make the
 *   Phase 4 house-cut switch trivial.
 * - [refundEach] is `stake.amount` per side when [outcomeKind] ==
 *   `refunded_both`; `0` when `winner_paid`.
 *
 * # [outcomeKind]
 *
 * `winner_paid` | `refunded_both` — keeping it as a string keeps the wire
 * shape additive (per ADR-0030 forward-only commit pattern).
 */
public data class BettingPayoutDistributed(
    public val bettingMatchUuid: UUID,
    public val challengerId: PlayerId,
    public val opponentId: PlayerId,
    public val winnerId: PlayerId?,
    public val currency: Currency,
    public val winnerPayout: Long,
    public val refundEach: Long,
    public val outcomeKind: String,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(challengerId.uuid)

    init {
        require(outcomeKind.isNotBlank()) { "BettingPayoutDistributed.outcomeKind must not be blank" }
        require(winnerPayout >= 0L) { "winnerPayout must be >= 0, got $winnerPayout" }
        require(refundEach >= 0L) { "refundEach must be >= 0, got $refundEach" }
        require(challengerId != opponentId) { "challenger and opponent must differ" }
        when (outcomeKind) {
            "winner_paid" -> {
                require(winnerId != null) { "winner_paid requires winnerId non-null" }
                require(refundEach == 0L) { "winner_paid refundEach must be 0, got $refundEach" }
                require(winnerPayout > 0L) { "winner_paid winnerPayout must be > 0" }
            }
            "refunded_both" -> {
                require(winnerId == null) { "refunded_both must have winnerId null" }
                require(winnerPayout == 0L) { "refunded_both winnerPayout must be 0, got $winnerPayout" }
                require(refundEach > 0L) { "refunded_both refundEach must be > 0" }
            }
        }
    }
}

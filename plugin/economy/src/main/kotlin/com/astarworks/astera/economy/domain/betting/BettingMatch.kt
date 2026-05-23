package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * A 1v1 betting match record (Wave 5bet / ADR-0103).
 *
 * Created on `/astera-bet challenge` (status [BettingStatus.PendingAcceptance])
 * and walks the [BettingStatus] state machine until terminal (Resolved /
 * Declined / Expired / Cancelled).
 *
 * ## Why escrow lives off the [BettingMatch]
 *
 * Escrow state (whether the challenger / opponent stakes have actually been
 * debited and held) is tracked by `IBettingEscrowService` as a separate
 * concern, NOT as a field on [BettingMatch]. The reason: escrow has its own
 * persistence overlay (V39 `bet_escrows` table) and its own auditable
 * lifecycle (held → released to winner / refunded). Conflating it with the
 * match record would make it harder for the Postgres overlay (Wave 5bet:
 * `PostgresBettingMatchRepository` priority-100) to keep escrow refunds
 * atomic — the V38 / V39 split mirrors the same split between
 * `ICurrencyLedger` (V3 wallets) and per-purpose ledgers in ADR-0035.
 *
 * ## Resolution payload
 *
 * The [winnerId] / [endReason] / [matchId] fields are non-null **only** after
 * the status reaches [BettingStatus.Resolved]. While in any non-terminal
 * state, all three are null. [BettingMatch.resolve] enforces this — callers
 * should construct via [BettingMatch.initiate] / [BettingMatch.accept] /
 * [BettingMatch.resolve] / [BettingMatch.cancel] / [BettingMatch.expire] /
 * [BettingMatch.decline] rather than copy() directly.
 */
public data class BettingMatch(
    val id: BettingMatchId,
    val policyId: BettingPolicyId,
    val challengerId: PlayerId,
    val opponentId: PlayerId,
    val stake: BetStake,
    val status: BettingStatus,
    val createdAt: Instant,
    val acceptedAt: Instant?,
    val resolvedAt: Instant?,
    val acceptanceDeadline: Instant,
    val matchId: MatchId?,
    val winnerId: PlayerId?,
    val endReason: BettingEndReason?,
) {
    init {
        require(challengerId != opponentId) {
            "challenger and opponent must be distinct players, got both=$challengerId"
        }
        require(createdAt <= acceptanceDeadline) {
            "createdAt ($createdAt) must be <= acceptanceDeadline ($acceptanceDeadline)"
        }
        // Terminal-state invariants (defensive — use cases must use named transitions).
        when (status) {
            BettingStatus.PendingAcceptance -> {
                require(acceptedAt == null && resolvedAt == null && matchId == null &&
                    winnerId == null && endReason == null) {
                    "PendingAcceptance must have no acceptedAt/resolvedAt/matchId/winnerId/endReason"
                }
            }
            BettingStatus.Active -> {
                require(acceptedAt != null && resolvedAt == null && winnerId == null &&
                    endReason == null) {
                    "Active requires acceptedAt non-null and no resolution fields"
                }
            }
            BettingStatus.Resolved -> {
                require(acceptedAt != null && resolvedAt != null && endReason != null) {
                    "Resolved requires acceptedAt + resolvedAt + endReason non-null"
                }
                // winnerId may be null for a draw.
            }
            BettingStatus.Declined, BettingStatus.Expired -> {
                require(resolvedAt == null && winnerId == null && endReason == null &&
                    matchId == null) {
                    "Pre-active terminal ($status) must have no resolution / match fields"
                }
            }
            BettingStatus.Cancelled -> {
                // Cancelled is reachable from Active — preserve acceptedAt + matchId
                // so the audit + escrow refund flow can still find the underlying PvP
                // match record.
                require(acceptedAt != null && resolvedAt == null && winnerId == null &&
                    endReason == null) {
                    "Cancelled requires acceptedAt non-null and no resolution fields"
                }
            }
        }
    }

    /** Pot before payout-split is applied: `2 × stake.amount`. */
    public fun pot(): Long = stake.amount * 2L

    public companion object {
        public fun initiate(
            id: BettingMatchId,
            policy: BettingPolicy,
            challengerId: PlayerId,
            opponentId: PlayerId,
            stake: BetStake,
            now: Instant,
        ): BettingMatch = BettingMatch(
            id = id,
            policyId = policy.id,
            challengerId = challengerId,
            opponentId = opponentId,
            stake = stake,
            status = BettingStatus.PendingAcceptance,
            createdAt = now,
            acceptedAt = null,
            resolvedAt = null,
            acceptanceDeadline = now.plusMillis(policy.acceptanceTimeout.inWholeMilliseconds),
            matchId = null,
            winnerId = null,
            endReason = null,
        )
    }

    public fun accept(now: Instant, matchId: MatchId): BettingMatch {
        check(status == BettingStatus.PendingAcceptance) {
            "cannot accept from status=$status (only PendingAcceptance)"
        }
        return copy(status = BettingStatus.Active, acceptedAt = now, matchId = matchId)
    }

    public fun decline(): BettingMatch {
        check(status == BettingStatus.PendingAcceptance) {
            "cannot decline from status=$status"
        }
        return copy(status = BettingStatus.Declined)
    }

    public fun expire(): BettingMatch {
        check(status == BettingStatus.PendingAcceptance) {
            "cannot expire from status=$status"
        }
        return copy(status = BettingStatus.Expired)
    }

    public fun cancel(): BettingMatch {
        check(status == BettingStatus.Active) {
            "cannot cancel from status=$status (only Active)"
        }
        return copy(status = BettingStatus.Cancelled)
    }

    public fun resolve(winnerId: PlayerId?, endReason: BettingEndReason, now: Instant): BettingMatch {
        check(status == BettingStatus.Active) {
            "cannot resolve from status=$status (only Active)"
        }
        if (winnerId != null) {
            require(winnerId == challengerId || winnerId == opponentId) {
                "winnerId ($winnerId) must be challenger or opponent"
            }
        }
        return copy(
            status = BettingStatus.Resolved,
            winnerId = winnerId,
            endReason = endReason,
            resolvedAt = now,
        )
    }
}

/**
 * Why a [BettingMatch] terminal-Resolved transition fired. Mirrors
 * [com.astarworks.astera.core.domain.event.EndReason] but specialized to the
 * subset that betting cares about.
 */
public sealed interface BettingEndReason {
    /** Normal PvP completion — one side won according to the match outcome. */
    public data object MatchOutcome : BettingEndReason
    /** Both sides scored equal / timeout — refund both stakes. */
    public data object Draw : BettingEndReason
    /** Admin cancelled the underlying match (refund both). */
    public data object AdminCancelled : BettingEndReason
    /** Underlying match cancelled with a reason (refund both). */
    public data class CancelledWithReason(val why: String) : BettingEndReason
}

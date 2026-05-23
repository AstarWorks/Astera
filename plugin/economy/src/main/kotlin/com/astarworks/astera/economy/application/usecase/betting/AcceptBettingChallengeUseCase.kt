package com.astarworks.astera.economy.application.usecase.betting

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingEscrowService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingOptInProvider
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.economy.domain.betting.BettingStatus
import java.time.Clock

/**
 * Walks a [BettingMatch] from
 * [com.astarworks.astera.economy.domain.betting.BettingStatus.PendingAcceptance]
 * → [com.astarworks.astera.economy.domain.betting.BettingStatus.Active] by
 * escrowing both stakes and binding the underlying PvP [MatchId]
 * (Wave 5bet / ADR-0103 §"Workflow" step 2 + 3).
 *
 * Caller (typically the 1v1 lobby acceptance flow) must supply the actual
 * [matchId] of the spun-up PvP match — this is normally minted by Wave 7b
 * Ranked queue. For Wave 5bet PoC the caller mints a lower-kebab-case id
 * such as `bet-<short-uuid>`.
 *
 * On accept:
 *
 * 1. Look up the match by [bettingMatchId].
 * 2. Reject if not PendingAcceptance or if deadline has passed.
 * 3. Reject if accepter is neither challenger nor opponent.
 * 4. Re-check opt-in for the opponent (defensive — they may have toggled off).
 * 5. Escrow both sides via [IBettingEscrowService.hold].
 * 6. Update status → Active.
 * 7. Publish two [CurrencyChanged] events (one per side) tagged with
 *    [CurrencyChangeReason.Betting] kind=`escrow_debit` so analytics +
 *    audit can attribute the debit.
 */
public class AcceptBettingChallengeUseCase(
    private val matches: IBettingMatchRepository,
    private val escrow: IBettingEscrowService,
    private val optIn: IBettingOptInProvider,
    private val ledger: ICurrencyLedger,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
) {

    public fun execute(
        bettingMatchId: BettingMatchId,
        accepterId: PlayerId,
        matchId: MatchId,
    ): Result<BettingMatch, BettingError> {
        val now = clock.instant()

        val match = matches.findById(bettingMatchId)
            ?: return Result.failure(BettingError.UnknownMatch(bettingMatchId))
        if (match.status != BettingStatus.PendingAcceptance) {
            return Result.failure(BettingError.NotPending(bettingMatchId, match.status))
        }
        if (now.isAfter(match.acceptanceDeadline)) {
            return Result.failure(
                BettingError.AcceptanceDeadlineExpired(bettingMatchId, match.acceptanceDeadline),
            )
        }
        if (accepterId != match.challengerId && accepterId != match.opponentId) {
            return Result.failure(BettingError.NotInvited(bettingMatchId, accepterId))
        }
        if (!optIn.isOptedIn(match.opponentId)) {
            return Result.failure(BettingError.PlayerOptedOut(match.opponentId))
        }
        if (!optIn.isOptedIn(match.challengerId)) {
            return Result.failure(BettingError.PlayerOptedOut(match.challengerId))
        }

        val held = escrow.hold(
            bettingMatchId = bettingMatchId,
            challengerId = match.challengerId,
            opponentId = match.opponentId,
            currency = match.stake.currency,
            perSideAmount = match.stake.amount,
            now = now,
        )
        if (held is Result.Failure) return Result.failure(held.error)

        val updated = matches.save(match.accept(now, matchId))

        publishEscrowDebit(updated.challengerId, updated)
        publishEscrowDebit(updated.opponentId, updated)
        return Result.success(updated)
    }

    private fun publishEscrowDebit(playerId: PlayerId, match: BettingMatch) {
        val balance = ledger.balance(playerId, match.stake.currency)
        eventBus.publish(
            CurrencyChanged(
                playerId = playerId,
                currency = match.stake.currency,
                delta = -match.stake.amount,
                newBalance = balance,
                reason = CurrencyChangeReason.Betting(
                    bettingMatchUuid = match.id.value,
                    kind = "escrow_debit",
                ),
            ),
        )
    }
}

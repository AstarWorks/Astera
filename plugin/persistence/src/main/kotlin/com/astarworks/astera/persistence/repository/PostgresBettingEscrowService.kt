package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingEscrowService
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingEscrow
import com.astarworks.astera.economy.domain.betting.BettingEscrowStatus
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import com.astarworks.astera.persistence.table.BetEscrowsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IBettingEscrowService] (Wave 5bet / ADR-0103).
 *
 * Each operation wraps both the wallet movement (via the injected
 * [ICurrencyLedger] — typically [PostgresCurrencyLedger]) AND the escrow
 * row update in a single SQL transaction so partial failures roll back
 * cleanly. The InMemory impl in the economy plugin does best-effort
 * compensating credits; the Postgres overlay does proper transactional
 * atomicity.
 */
public class PostgresBettingEscrowService(
    private val db: Database,
    private val ledger: ICurrencyLedger,
) : IBettingEscrowService {

    override fun hold(
        bettingMatchId: BettingMatchId,
        challengerId: PlayerId,
        opponentId: PlayerId,
        currency: Currency,
        perSideAmount: Long,
        now: Instant,
    ): Result<BettingEscrow, BettingError> = transaction(db) {
        require(challengerId != opponentId) { "challenger and opponent must differ" }
        require(perSideAmount > 0L) { "perSideAmount must be > 0, got $perSideAmount" }

        val existing = BetEscrowsTable
            .selectAll()
            .where { BetEscrowsTable.bettingMatchId eq bettingMatchId.value }
            .firstOrNull()
        if (existing != null) {
            return@transaction Result.failure(BettingError.AlreadyEscrowed(bettingMatchId))
        }

        val challengerDebit = ledger.debit(challengerId, currency, perSideAmount)
        if (challengerDebit is Result.Failure) {
            return@transaction Result.failure(BettingError.EscrowDebitFailed(challengerId, challengerDebit.error))
        }
        val opponentDebit = ledger.debit(opponentId, currency, perSideAmount)
        if (opponentDebit is Result.Failure) {
            // Compensate the challenger; whole transaction rolls back on the implicit
            // throw path used by transaction { } if we were to throw, but here we
            // return a Failure value — so apply the credit explicitly.
            ledger.credit(challengerId, currency, perSideAmount)
            return@transaction Result.failure(BettingError.EscrowDebitFailed(opponentId, opponentDebit.error))
        }

        val escrow = BettingEscrow(
            bettingMatchId = bettingMatchId,
            challengerId = challengerId,
            opponentId = opponentId,
            currency = currency,
            perSideAmount = perSideAmount,
            status = BettingEscrowStatus.Held,
            heldAt = now,
            settledAt = null,
        )
        BetEscrowsTable.upsert(BetEscrowsTable.bettingMatchId) {
            it[BetEscrowsTable.bettingMatchId] = escrow.bettingMatchId.value
            it[BetEscrowsTable.challengerId] = escrow.challengerId.uuid
            it[BetEscrowsTable.opponentId] = escrow.opponentId.uuid
            it[BetEscrowsTable.currency] = escrow.currency.name
            it[BetEscrowsTable.perSideAmount] = escrow.perSideAmount
            it[BetEscrowsTable.status] = escrow.status.name
            it[BetEscrowsTable.heldAt] = escrow.heldAt
            it[BetEscrowsTable.settledAt] = null
        }
        Result.success(escrow)
    }

    override fun releaseToWinner(
        bettingMatchId: BettingMatchId,
        winnerId: PlayerId,
        payoutSplit: Double,
        now: Instant,
    ): Result<BettingEscrow, BettingError> = transaction(db) {
        require(payoutSplit > 0.0 && payoutSplit <= 1.0) {
            "payoutSplit must be in (0.0, 1.0], got $payoutSplit"
        }
        val current = loadEscrow(bettingMatchId)
            ?: return@transaction Result.failure(BettingError.EscrowMissing(bettingMatchId))
        if (current.status == BettingEscrowStatus.Paid) {
            return@transaction Result.success(current)
        }
        require(current.status == BettingEscrowStatus.Held) {
            "cannot release escrow in status ${current.status}"
        }
        require(winnerId == current.challengerId || winnerId == current.opponentId) {
            "winnerId ($winnerId) must be challenger or opponent of escrow $bettingMatchId"
        }
        val pot = current.perSideAmount * 2L
        val payout = ((pot.toDouble() * payoutSplit).toLong())
        if (payout > 0L) {
            ledger.credit(winnerId, current.currency, payout)
        }
        val updated = current.copy(status = BettingEscrowStatus.Paid, settledAt = now)
        writeBack(updated)
        Result.success(updated)
    }

    override fun refundBoth(
        bettingMatchId: BettingMatchId,
        now: Instant,
    ): Result<BettingEscrow, BettingError> = transaction(db) {
        val current = loadEscrow(bettingMatchId)
            ?: return@transaction Result.failure(BettingError.EscrowMissing(bettingMatchId))
        if (current.status == BettingEscrowStatus.Refunded || current.status == BettingEscrowStatus.Paid) {
            return@transaction Result.success(current)
        }
        require(current.status == BettingEscrowStatus.Held) {
            "cannot refund escrow in status ${current.status}"
        }
        ledger.credit(current.challengerId, current.currency, current.perSideAmount)
        ledger.credit(current.opponentId, current.currency, current.perSideAmount)
        val updated = current.copy(status = BettingEscrowStatus.Refunded, settledAt = now)
        writeBack(updated)
        Result.success(updated)
    }

    override fun find(bettingMatchId: BettingMatchId): BettingEscrow? = transaction(db) {
        loadEscrow(bettingMatchId)
    }

    private fun loadEscrow(bettingMatchId: BettingMatchId): BettingEscrow? =
        BetEscrowsTable
            .selectAll()
            .where { BetEscrowsTable.bettingMatchId eq bettingMatchId.value }
            .firstOrNull()
            ?.toDomainOrNull()

    private fun writeBack(escrow: BettingEscrow) {
        BetEscrowsTable.upsert(BetEscrowsTable.bettingMatchId) {
            it[BetEscrowsTable.bettingMatchId] = escrow.bettingMatchId.value
            it[BetEscrowsTable.challengerId] = escrow.challengerId.uuid
            it[BetEscrowsTable.opponentId] = escrow.opponentId.uuid
            it[BetEscrowsTable.currency] = escrow.currency.name
            it[BetEscrowsTable.perSideAmount] = escrow.perSideAmount
            it[BetEscrowsTable.status] = escrow.status.name
            it[BetEscrowsTable.heldAt] = escrow.heldAt
            it[BetEscrowsTable.settledAt] = escrow.settledAt
        }
    }

    private fun ResultRow.toDomainOrNull(): BettingEscrow? {
        val statusName = this[BetEscrowsTable.status]
        val status = runCatching { BettingEscrowStatus.valueOf(statusName) }.getOrNull() ?: return null
        val currencyName = this[BetEscrowsTable.currency]
        val currency = runCatching { Currency.valueOf(currencyName) }.getOrNull() ?: return null
        return runCatching {
            BettingEscrow(
                bettingMatchId = BettingMatchId(this[BetEscrowsTable.bettingMatchId]),
                challengerId = PlayerId(this[BetEscrowsTable.challengerId]),
                opponentId = PlayerId(this[BetEscrowsTable.opponentId]),
                currency = currency,
                perSideAmount = this[BetEscrowsTable.perSideAmount],
                status = status,
                heldAt = this[BetEscrowsTable.heldAt],
                settledAt = this[BetEscrowsTable.settledAt],
            )
        }.getOrNull()
    }
}

package com.astarworks.astera.core.application.service.loginbonus

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusRegistry
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginBonusService
import com.astarworks.astera.core.application.port.outbound.loginbonus.ILoginStreakRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.LoginBonusClaimed
import com.astarworks.astera.core.domain.loginbonus.LoginBonusClaim
import com.astarworks.astera.core.domain.loginbonus.LoginBonusError
import com.astarworks.astera.core.domain.loginbonus.LoginBonusStatus
import com.astarworks.astera.core.domain.loginbonus.LoginStreakRecord
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * [ILoginBonusService] (ADR-0210). The reward currency is credited through the shared
 * [ICurrencyLedger] and announced as `CurrencyChanged(LoginBonus)` so wallet HUDs update.
 *
 * Reward `items` are carried in the claim but not delivered here: core has no inventory
 * port; ship currency-only rotations until a delivery subscriber exists.
 *
 * Claims for the same player are serialised so a double join event cannot pay twice.
 */
public class DefaultLoginBonusService(
    private val registry: ILoginBonusRegistry,
    private val streakRepo: ILoginStreakRepository,
    private val ledger: () -> ICurrencyLedger?,
    private val events: IDomainEventBus,
) : ILoginBonusService {

    private val locks = ConcurrentHashMap<PlayerId, Any>()

    override fun claimDaily(
        playerId: PlayerId,
        today: LocalDate,
    ): Result<LoginBonusClaim, LoginBonusError> = synchronized(locks.computeIfAbsent(playerId) { Any() }) {
        val record = streakRepo.findByPlayer(playerId)
        if (record != null && record.lastClaimDate == today) {
            return@synchronized Result.failure(LoginBonusError.AlreadyClaimedToday)
        }
        val streak = nextStreak(record, today)
        val day = rotationDay(streak)
        val reward = day?.let { registry.forDay(it)?.reward }
            ?: return@synchronized Result.failure(LoginBonusError.NoRewardForDay(day ?: streak))

        if (reward.amount > 0L) {
            val wallet = ledger()?.credit(playerId, reward.currency, reward.amount)
            if (wallet != null) {
                events.publish(
                    CurrencyChanged(
                        playerId = playerId,
                        currency = reward.currency,
                        delta = reward.amount,
                        newBalance = wallet.balance(reward.currency),
                        reason = CurrencyChangeReason.LoginBonus,
                    ),
                )
            }
        }
        streakRepo.upsert(LoginStreakRecord(playerId, streak, today))
        events.publish(LoginBonusClaimed(playerId, streak, day, reward))
        Result.success(LoginBonusClaim(streakDays = streak, day = day, reward = reward))
    }

    override fun status(playerId: PlayerId, today: LocalDate): LoginBonusStatus {
        val record = streakRepo.findByPlayer(playerId)
        val claimed = record != null && record.lastClaimDate == today
        val streak = if (claimed) record!!.streakDays else nextStreak(record, today)
        val day = rotationDay(streak) ?: 1
        val tomorrowDay = rotationDay(streak + 1)
        return LoginBonusStatus(
            claimedToday = claimed,
            streakDays = streak,
            day = day,
            reward = registry.forDay(day)?.reward,
            tomorrow = tomorrowDay?.let { registry.forDay(it)?.reward },
        )
    }

    override fun streakOf(playerId: PlayerId): LoginStreakRecord? = streakRepo.findByPlayer(playerId)

    private fun nextStreak(record: LoginStreakRecord?, today: LocalDate): Int =
        if (record != null && record.lastClaimDate == today.minusDays(1)) record.streakDays + 1 else 1

    /** 1-based rotation day for [streak], wrapping at the highest configured day; `null` when no day is configured. */
    private fun rotationDay(streak: Int): Int? {
        val length = registry.all().maxOfOrNull { it.day } ?: return null
        return (streak - 1) % length + 1
    }
}

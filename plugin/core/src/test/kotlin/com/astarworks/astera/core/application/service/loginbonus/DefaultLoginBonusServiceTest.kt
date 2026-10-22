package com.astarworks.astera.core.application.service.loginbonus

import com.astarworks.astera.core.application.contents.LoginBonusContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.event.LoginBonusClaimed
import com.astarworks.astera.core.domain.loginbonus.LoginBonusError
import com.astarworks.astera.core.domain.loginbonus.LoginBonusReward
import com.astarworks.astera.core.domain.loginbonus.LoginBonusSpec
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class DefaultLoginBonusServiceTest {

    private val player = PlayerId(UUID.randomUUID())
    private val ledger = FakeLedger()
    private val bus = InMemoryDomainEventBus()
    private val streaks = InMemoryLoginStreakRepository()
    private val registry = LoginBonusContents().also { reg ->
        listOf(10L, 20L, 50L).forEachIndexed { i, amount ->
            reg.register(LoginBonusSpec(i + 1, LoginBonusReward(Currency.STAR, amount)))
        }
    }
    private val service = DefaultLoginBonusService(registry, streaks, { ledger }, bus)
    private val d1 = LocalDate.of(2026, 10, 20)

    @Test
    fun `first claim pays day 1, credits the wallet, stores the streak and publishes`() {
        val seen = mutableListOf<Any>()
        bus.subscribe(LoginBonusClaimed::class) { seen += it }
        bus.subscribe(CurrencyChanged::class) { seen += it }

        val claim = (service.claimDaily(player, d1) as Result.Success).value

        assertThat(claim.streakDays).isEqualTo(1)
        assertThat(claim.day).isEqualTo(1)
        assertThat(claim.reward.amount).isEqualTo(10L)
        assertThat(ledger.balance(player, Currency.STAR)).isEqualTo(10L)
        assertThat(service.streakOf(player)!!.lastClaimDate).isEqualTo(d1)
        assertThat(seen.map { it::class.simpleName }).containsExactlyInAnyOrder("LoginBonusClaimed", "CurrencyChanged")
    }

    @Test
    fun `claiming twice on the same day is rejected and pays nothing more`() {
        service.claimDaily(player, d1)

        val again = service.claimDaily(player, d1)

        assertThat((again as Result.Failure).error).isEqualTo(LoginBonusError.AlreadyClaimedToday)
        assertThat(ledger.balance(player, Currency.STAR)).isEqualTo(10L)
    }

    @Test
    fun `consecutive days grow the streak and the rotation wraps after the last day`() {
        val paid = (0..3).map { offset ->
            (service.claimDaily(player, d1.plusDays(offset.toLong())) as Result.Success).value
        }

        assertThat(paid.map { it.streakDays }).containsExactly(1, 2, 3, 4)
        assertThat(paid.map { it.day }).containsExactly(1, 2, 3, 1)
        assertThat(paid.map { it.reward.amount }).containsExactly(10L, 20L, 50L, 10L)
    }

    @Test
    fun `a missed day restarts the streak at day 1`() {
        service.claimDaily(player, d1)
        service.claimDaily(player, d1.plusDays(1))

        val claim = (service.claimDaily(player, d1.plusDays(3)) as Result.Success).value

        assertThat(claim.streakDays).isEqualTo(1)
        assertThat(claim.day).isEqualTo(1)
    }

    @Test
    fun `status previews the next claim without writing`() {
        service.claimDaily(player, d1)

        val tomorrow = service.status(player, d1.plusDays(1))
        val today = service.status(player, d1)

        assertThat(tomorrow.claimedToday).isFalse
        assertThat(tomorrow.streakDays).isEqualTo(2)
        assertThat(tomorrow.reward!!.amount).isEqualTo(20L)
        assertThat(tomorrow.tomorrow!!.amount).isEqualTo(50L)
        assertThat(today.claimedToday).isTrue
        assertThat(service.streakOf(player)!!.streakDays).isEqualTo(1)
    }

    @Test
    fun `an empty table is reported, not paid`() {
        val empty = DefaultLoginBonusService(LoginBonusContents(), streaks, { ledger }, bus)

        val result = empty.claimDaily(player, d1)

        assertThat((result as Result.Failure).error).isInstanceOf(LoginBonusError.NoRewardForDay::class.java)
        assertThat(streaks.findByPlayer(player)).isNull()
    }

    private class FakeLedger : ICurrencyLedger {
        private val wallets = HashMap<PlayerId, Wallet>()
        override fun balance(playerId: PlayerId, currency: Currency): Long = wallet(playerId).balance(currency)
        override fun wallet(playerId: PlayerId): Wallet = wallets.getOrPut(playerId) { Wallet(SimpleAttributeContainer()) }
        override fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError> =
            wallet(playerId).withdraw(currency, amount)
        override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet =
            wallet(playerId).deposit(currency, amount)
        override fun transfer(from: PlayerId, to: PlayerId, currency: Currency, amount: Long): Result<TransferResult, WalletError> =
            throw UnsupportedOperationException()
    }
}

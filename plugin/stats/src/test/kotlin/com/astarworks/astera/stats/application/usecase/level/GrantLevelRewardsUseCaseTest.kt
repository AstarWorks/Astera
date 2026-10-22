package com.astarworks.astera.stats.application.usecase.level

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.LevelRewardError
import com.astarworks.astera.stats.application.service.InMemoryLevelRewardRepository
import com.astarworks.astera.stats.domain.level.LevelReward
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeMessageRenderer
import com.astarworks.astera.testfixtures.FakePlayerGateway
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class GrantLevelRewardsUseCaseTest {
    private val player = PlayerId(UUID.randomUUID())
    private val wallet = Wallet(SimpleAttributeContainer())
    private val ledger = object : ICurrencyLedger {
        override fun balance(playerId: PlayerId, currency: Currency): Long = wallet.balance(currency)
        override fun wallet(playerId: PlayerId): Wallet = wallet
        override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet = wallet.deposit(currency, amount)
        override fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError> =
            wallet.withdraw(currency, amount)
        override fun transfer(from: PlayerId, to: PlayerId, currency: Currency, amount: Long): Result<TransferResult, WalletError> =
            error("Not used")
    }
    private val repository = InMemoryLevelRewardRepository { ledger }
    private val players = FakePlayerGateway()
    private val messages = FakeMessageRenderer()
    private val hud = FakeHudGateway()
    private val table = mapOf(
        2 to LevelReward(star = 50, titles = listOf("newcomer")),
        3 to LevelReward(star = 75, gold = 2, cosmetics = listOf("spark"), items = listOf("training-sword")),
    )
    private fun useCase() = GrantLevelRewardsUseCase(table, repository, players, messages, hud)

    @Test
    fun `multi-level jump grants every tier once and a rebuilt use case does not regrant`() {
        assertThat(useCase().execute(player, 1).getOrNull()).isZero()
        assertThat(useCase().execute(player, 3).getOrNull()).isEqualTo(2)
        assertThat(wallet.balance(Currency.STAR)).isEqualTo(125)
        assertThat(wallet.balance(Currency.GOLD)).isEqualTo(2)
        assertThat(repository.unlocks(player)).containsExactlyInAnyOrder(
            "title:newcomer", "cosmetic:spark", "item:training-sword",
        )
        assertThat(useCase().execute(player, 3).getOrNull()).isZero()
        assertThat(wallet.balance(Currency.STAR)).isEqualTo(125)
        assertThat(players.messages).hasSize(2)
        assertThat(hud.operations.filterIsInstance<FakeHudGateway.HudOp.Shown>()).hasSize(2)
        assertThat(messages.calls.map { it.placeholders["level"] }).containsExactly("2", "3")
    }

    @Test
    fun `unavailable ledger leaves rewards eligible for retry`() {
        var available = false
        val repo = InMemoryLevelRewardRepository { if (available) ledger else null }
        val rewards = GrantLevelRewardsUseCase(table, repo, players, messages, hud)
        assertThat(rewards.execute(player, 3).errorOrNull()).isEqualTo(LevelRewardError.LedgerUnavailable)
        assertThat(repo.unlocks(player)).isEmpty()
        assertThat(players.messages).isEmpty()
        available = true
        assertThat(rewards.execute(player, 3).getOrNull()).isEqualTo(2)
    }

    @Test
    fun `invalid content fails before applying a reward`() {
        assertThatThrownBy { LevelReward(star = -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { LevelReward(items = listOf("bad id")) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

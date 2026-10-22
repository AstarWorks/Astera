package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.gui.ResolvedReactiveSlot
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [DefaultGuiBindingResolver] (Wave 4.5 + Wave 5f RTM #21).
 *
 * Uses inline fakes so we don't reach into `plugin/test-fixtures` (worker
 * territory boundary). Wave 5f adds coverage for:
 * - `player.coins.<currency>` (wallet binding)
 * - `player.kills` (stat binding via `IAttributeContainer`)
 * - graceful degradation when providers return null (publishing plugin not loaded yet)
 */
class DefaultGuiBindingResolverTest {

    /** Inline fake of [ICurrencyLedger] sufficient for `balance` reads. */
    private class FakeLedger(private val balances: Map<Pair<PlayerId, Currency>, Long>) : ICurrencyLedger {
        override fun balance(playerId: PlayerId, currency: Currency): Long =
            balances[playerId to currency] ?: 0L

        override fun wallet(playerId: PlayerId): Wallet =
            throw UnsupportedOperationException("not used by resolver")

        override fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError> =
            throw UnsupportedOperationException("not used by resolver")

        override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet =
            throw UnsupportedOperationException("not used by resolver")

        override fun transfer(
            from: PlayerId,
            to: PlayerId,
            currency: Currency,
            amount: Long,
        ): Result<TransferResult, WalletError> =
            throw UnsupportedOperationException("not used by resolver")
    }

    /** Minimal [IAttributeContainer] backed by a map — exercises [effectiveNumber] default. */
    private class FakeContainer(private val values: MutableMap<AttributeKey, AttributeValue> = mutableMapOf()) :
        IAttributeContainer {
        override fun get(key: AttributeKey): AttributeValue? = values[key]
        override fun set(key: AttributeKey, value: AttributeValue) {
            values[key] = value
        }
        override fun remove(key: AttributeKey) {
            values.remove(key)
        }
        override fun keys(): Set<AttributeKey> = values.keys
    }

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    // ----- player.coins.<currency> (Wave 5f new) ----------------------------

    @Test
    fun `resolve player_coins_star returns indicator-coin-star with balance count`() {
        val player = playerId()
        val ledger = FakeLedger(mapOf((player to Currency.STAR) to 7L))
        val resolver = DefaultGuiBindingResolver(wallet = ledger)

        val result = resolver.resolve(BindingKey("player.coins.star"), player)

        assertThat(result).isNotNull
        requireNotNull(result)
        assertThat(result.itemId).isEqualTo(ItemId("indicator-coin-star"))
        assertThat(result.countOverride).isEqualTo(7)
        assertThat(result.displayOverride).isEqualTo(MessageKey("astera.gui.binding.coins.value"))
    }

    @Test
    fun `resolve player_coins_gold clamps zero balance to 1`() {
        val player = playerId()
        val ledger = FakeLedger(emptyMap()) // balance returns 0
        val resolver = DefaultGuiBindingResolver(wallet = ledger)

        val result = resolver.resolve(BindingKey("player.coins.gold"), player)

        assertThat(result?.itemId).isEqualTo(ItemId("indicator-coin-gold"))
        // Vanilla stack count can't be 0; resolver clamps into [1, 64]
        assertThat(result?.countOverride).isEqualTo(1)
    }

    @Test
    fun `resolve player_coins_gem clamps very large balance to 64`() {
        val player = playerId()
        val ledger = FakeLedger(mapOf((player to Currency.GEM) to 9_999_999L))
        val resolver = DefaultGuiBindingResolver(wallet = ledger)

        val result = resolver.resolve(BindingKey("player.coins.gem"), player)

        assertThat(result?.countOverride).isEqualTo(64)
    }

    @Test
    fun `resolve unknown currency token returns null`() {
        val player = playerId()
        val ledger = FakeLedger(emptyMap())
        val resolver = DefaultGuiBindingResolver(wallet = ledger)

        assertThat(resolver.resolve(BindingKey("player.coins.dogecoin"), player)).isNull()
    }

    @Test
    fun `resolve player_coins_star degrades to null when wallet provider returns null`() {
        // Economy plugin not yet loaded → walletProvider returns null
        val resolver = DefaultGuiBindingResolver(
            walletProvider = { null },
        )

        val result = resolver.resolve(BindingKey("player.coins.star"), playerId())

        assertThat(result).isNull()
    }

    // ----- player.kills (Wave 5f new) ---------------------------------------

    @Test
    fun `resolve player_kills returns indicator-kills with kill count`() {
        val player = playerId()
        val container = FakeContainer()
        container.set(Attributes.STAT_KILLS, AttributeValue.NumberValue(12.0))

        val resolver = DefaultGuiBindingResolver(
            stats = { p -> if (p == player) container else null },
        )

        val result = resolver.resolve(BindingKey("player.kills"), player)

        assertThat(result).isNotNull
        requireNotNull(result)
        assertThat(result.itemId).isEqualTo(ItemId("indicator-kills"))
        assertThat(result.countOverride).isEqualTo(12)
        assertThat(result.displayOverride).isEqualTo(MessageKey("astera.gui.binding.kills.value"))
    }

    @Test
    fun `resolve player_kills clamps zero kills to 1`() {
        val player = playerId()
        val container = FakeContainer() // STAT_KILLS unset → effectiveNumber == 0

        val resolver = DefaultGuiBindingResolver(
            stats = { container },
        )

        val result = resolver.resolve(BindingKey("player.kills"), player)

        assertThat(result?.countOverride).isEqualTo(1)
    }

    @Test
    fun `resolve player_kills clamps very large kill count to 64`() {
        val player = playerId()
        val container = FakeContainer()
        container.set(Attributes.STAT_KILLS, AttributeValue.NumberValue(999.0))

        val resolver = DefaultGuiBindingResolver(
            stats = { container },
        )

        val result = resolver.resolve(BindingKey("player.kills"), player)

        assertThat(result?.countOverride).isEqualTo(64)
    }

    @Test
    fun `resolve player_kills degrades to null when stats provider returns null`() {
        val resolver = DefaultGuiBindingResolver(
            playerStatsProvider = { null },
        )

        val result = resolver.resolve(BindingKey("player.kills"), playerId())

        assertThat(result).isNull()
    }

    // ----- Fallthrough ------------------------------------------------------

    @Test
    fun `resolve unsupported binding prefix returns null`() {
        val resolver = DefaultGuiBindingResolver(
                        walletProvider = { FakeLedger(emptyMap()) },
            playerStatsProvider = { FakeContainer() },
        )

        assertThat(resolver.resolve(BindingKey("server.tps"), playerId())).isNull()
        assertThat(resolver.resolve(BindingKey("player.unknown.field"), playerId())).isNull()
    }
}

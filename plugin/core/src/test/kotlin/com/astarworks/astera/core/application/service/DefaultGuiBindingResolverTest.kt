package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.ILoadoutRepository
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
import com.astarworks.astera.core.domain.player.Loadout
import com.astarworks.astera.core.domain.player.LoadoutSlot
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

    /** Inline fake of [ILoadoutRepository]; backed by a mutable map. */
    private class FakeLoadoutRepo : ILoadoutRepository {
        private val store: MutableMap<PlayerId, Loadout> = mutableMapOf()

        override fun find(playerId: PlayerId): Loadout? = store[playerId]

        override fun save(playerId: PlayerId, loadout: Loadout) {
            store[playerId] = loadout
        }

        override fun all(): Collection<Pair<PlayerId, Loadout>> =
            store.entries.map { it.key to it.value }
    }

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

    // ----- player.loadout.<slot> (regression) -------------------------------

    @Test
    fun `resolve player_loadout_main returns the configured ItemId wrapped in ResolvedReactiveSlot`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(loadouts = repo)

        val result = resolver.resolve(BindingKey("player.loadout.main"), player)

        assertThat(result).isEqualTo(ResolvedReactiveSlot(itemId = ItemId("example-sword")))
        // Numeric overrides remain null — loadout bindings only pick an icon
        assertThat(result?.displayOverride).isNull()
        assertThat(result?.loreOverride).isNull()
        assertThat(result?.countOverride).isNull()
    }

    @Test
    fun `resolve player_loadout_sub returns null when slot is empty`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(loadouts = repo)

        assertThat(resolver.resolve(BindingKey("player.loadout.sub"), player)).isNull()
    }

    @Test
    fun `resolve when viewer is null returns null`() {
        val repo = FakeLoadoutRepo()
        repo.save(playerId(), Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(loadouts = repo)

        assertThat(resolver.resolve(BindingKey("player.loadout.main"), viewer = null)).isNull()
    }

    @Test
    fun `resolve when loadout repo has no entry returns null`() {
        val repo = FakeLoadoutRepo() // empty
        val resolver = DefaultGuiBindingResolver(loadouts = repo)

        assertThat(resolver.resolve(BindingKey("player.loadout.main"), playerId())).isNull()
    }

    @Test
    fun `resolve unknown slot token returns null`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.MAIN to ItemId("example-sword"))))

        val resolver = DefaultGuiBindingResolver(loadouts = repo)

        assertThat(resolver.resolve(BindingKey("player.loadout.invalid"), player)).isNull()
    }

    @Test
    fun `every LoadoutSlot variant is resolvable via its lowercase token`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()

        val pins: Map<LoadoutSlot, ItemId> = LoadoutSlot.entries
            .associateWith { ItemId("pin-${it.name.lowercase()}") }
        repo.save(player, Loadout(pins))

        val resolver = DefaultGuiBindingResolver(loadouts = repo)

        LoadoutSlot.entries.forEach { slot ->
            val key = BindingKey("player.loadout.${slot.name.lowercase()}")
            val resolved = resolver.resolve(key, player)
            assertThat(resolved?.itemId)
                .describedAs("slot %s should resolve via token %s", slot, slot.name.lowercase())
                .isEqualTo(ItemId("pin-${slot.name.lowercase()}"))
        }
    }

    @Test
    fun `lazy provider variant defers loadout repo lookup until resolve is called`() {
        val repo = FakeLoadoutRepo()
        val player = playerId()
        repo.save(player, Loadout(mapOf(LoadoutSlot.ULTIMATE to ItemId("nova-blade"))))

        var providerCalls = 0
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = {
                providerCalls += 1
                repo
            },
        )

        assertThat(providerCalls).isZero()

        val result = resolver.resolve(BindingKey("player.loadout.ultimate"), player)

        assertThat(result?.itemId).isEqualTo(ItemId("nova-blade"))
        assertThat(providerCalls).isEqualTo(1)
    }

    @Test
    fun `lazy provider is not called when viewer is null`() {
        var providerCalls = 0
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = {
                providerCalls += 1
                FakeLoadoutRepo()
            },
        )

        val result = resolver.resolve(BindingKey("player.loadout.main"), viewer = null)

        assertThat(result).isNull()
        assertThat(providerCalls).isZero()
    }

    @Test
    fun `lazy provider is not called for unknown slot tokens`() {
        var providerCalls = 0
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = {
                providerCalls += 1
                FakeLoadoutRepo()
            },
        )

        val result = resolver.resolve(BindingKey("player.loadout.invalid"), playerId())

        assertThat(result).isNull()
        assertThat(providerCalls).isZero()
    }

    // ----- player.coins.<currency> (Wave 5f new) ----------------------------

    @Test
    fun `resolve player_coins_star returns indicator-coin-star with balance count`() {
        val player = playerId()
        val ledger = FakeLedger(mapOf((player to Currency.STAR) to 7L))
        val resolver = DefaultGuiBindingResolver(loadouts = null, wallet = ledger)

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
        val resolver = DefaultGuiBindingResolver(loadouts = null, wallet = ledger)

        val result = resolver.resolve(BindingKey("player.coins.gold"), player)

        assertThat(result?.itemId).isEqualTo(ItemId("indicator-coin-gold"))
        // Vanilla stack count can't be 0; resolver clamps into [1, 64]
        assertThat(result?.countOverride).isEqualTo(1)
    }

    @Test
    fun `resolve player_coins_gem clamps very large balance to 64`() {
        val player = playerId()
        val ledger = FakeLedger(mapOf((player to Currency.GEM) to 9_999_999L))
        val resolver = DefaultGuiBindingResolver(loadouts = null, wallet = ledger)

        val result = resolver.resolve(BindingKey("player.coins.gem"), player)

        assertThat(result?.countOverride).isEqualTo(64)
    }

    @Test
    fun `resolve unknown currency token returns null`() {
        val player = playerId()
        val ledger = FakeLedger(emptyMap())
        val resolver = DefaultGuiBindingResolver(loadouts = null, wallet = ledger)

        assertThat(resolver.resolve(BindingKey("player.coins.dogecoin"), player)).isNull()
    }

    @Test
    fun `resolve player_coins_star degrades to null when wallet provider returns null`() {
        // Economy plugin not yet loaded → walletProvider returns null
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = { null },
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
            loadouts = null,
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
            loadouts = null,
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
            loadouts = null,
            stats = { container },
        )

        val result = resolver.resolve(BindingKey("player.kills"), player)

        assertThat(result?.countOverride).isEqualTo(64)
    }

    @Test
    fun `resolve player_kills degrades to null when stats provider returns null`() {
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = { null },
            playerStatsProvider = { null },
        )

        val result = resolver.resolve(BindingKey("player.kills"), playerId())

        assertThat(result).isNull()
    }

    // ----- Fallthrough ------------------------------------------------------

    @Test
    fun `resolve unsupported binding prefix returns null`() {
        val resolver = DefaultGuiBindingResolver(
            loadoutsProvider = { FakeLoadoutRepo() },
            walletProvider = { FakeLedger(emptyMap()) },
            playerStatsProvider = { FakeContainer() },
        )

        assertThat(resolver.resolve(BindingKey("server.tps"), playerId())).isNull()
        assertThat(resolver.resolve(BindingKey("player.unknown.field"), playerId())).isNull()
    }
}

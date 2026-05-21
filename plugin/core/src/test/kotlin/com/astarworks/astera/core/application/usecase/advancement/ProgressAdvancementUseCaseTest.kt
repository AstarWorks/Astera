package com.astarworks.astera.core.application.usecase.advancement

import com.astarworks.astera.core.application.contents.AdvancementContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.application.service.advancement.InMemoryPlayerAdvancementProgressRepository
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.advancement.AdvancementError
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.advancement.CustomAdvancement
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.event.AdvancementCompleted
import com.astarworks.astera.core.domain.event.AdvancementRewardGranted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [ProgressAdvancementUseCase] (Wave 5p, ADR-0088).
 *
 * Verifies:
 *  - Single-leaf criteria latch on first crossing.
 *  - Idempotent re-progress after completion.
 *  - Composite `AllOf` completes only when every leaf does.
 *  - Composite `AnyOf` completes when one leaf does.
 *  - Reward fan-out publishes the right events in declaration order.
 *  - Currency reward applies via `ICurrencyLedger.credit` synchronously.
 *  - Unknown advancement / invalid increment return typed errors.
 */
class ProgressAdvancementUseCaseTest {

    private val player = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val advId = AdvancementId("first-kill")
    private val iconItem = ItemId("example-sword")

    private lateinit var registry: AdvancementContents
    private lateinit var repo: InMemoryPlayerAdvancementProgressRepository
    private lateinit var events: InMemoryDomainEventBus
    private lateinit var capturedCompleted: MutableList<AdvancementCompleted>
    private lateinit var capturedRewards: MutableList<AdvancementRewardGranted>
    private lateinit var scheduler: FakeScheduler
    private lateinit var ledger: StubLedger
    private lateinit var useCase: ProgressAdvancementUseCase

    @BeforeEach
    fun setUp() {
        registry = AdvancementContents()
        repo = InMemoryPlayerAdvancementProgressRepository()
        events = InMemoryDomainEventBus()
        capturedCompleted = mutableListOf()
        capturedRewards = mutableListOf()
        events.subscribe(AdvancementCompleted::class) { capturedCompleted.add(it) }
        events.subscribe(AdvancementRewardGranted::class) { capturedRewards.add(it) }
        scheduler = FakeScheduler(startTick = 100L)
        ledger = StubLedger()
        val grantUseCase = GrantAdvancementRewardUseCase({ ledger }, events, scheduler)
        useCase = ProgressAdvancementUseCase(
            registry = registry,
            repository = repo,
            rewardGrant = grantUseCase,
            events = events,
            scheduler = scheduler,
        )
    }

    @Test
    fun `kill-count criterion latches on target reached`() {
        registry.register(
            CustomAdvancement(
                id = advId,
                titleKey = MessageKey("astera.advancement.first_kill.title"),
                descriptionKey = MessageKey("astera.advancement.first_kill.desc"),
                iconItemId = iconItem,
                criteria = AdvancementCriteria.KillCount(3),
                rewards = listOf(AdvancementReward.GrantCurrency(Currency.GOLD, 50)),
            ),
        )

        val r1 = useCase.progress(player, advId, 1)
        val r2 = useCase.progress(player, advId, 1)
        val r3 = useCase.progress(player, advId, 1)
        val r4 = useCase.progress(player, advId, 1) // already complete

        assertThat(r1).isInstanceOf(Result.Success::class.java)
        assertThat((r1 as Result.Success).value.completedAt).isNull()
        assertThat((r2 as Result.Success).value.completedAt).isNull()
        assertThat((r3 as Result.Success).value.completedAt).isNotNull()
        assertThat((r3.value.current)).isEqualTo(3)
        // Idempotent: same already-completed progress returned, no state change.
        assertThat((r4 as Result.Success).value.completedAt).isEqualTo(r3.value.completedAt)
        assertThat(capturedCompleted).hasSize(1)
        assertThat(capturedCompleted[0].advancementId).isEqualTo(advId)
        assertThat(capturedCompleted[0].atTick).isEqualTo(100L)
        // Reward fan-out published once.
        assertThat(capturedRewards).hasSize(1)
        assertThat(capturedRewards[0].reward).isEqualTo(AdvancementReward.GrantCurrency(Currency.GOLD, 50))
        // Currency credited synchronously.
        assertThat(ledger.balance(player, Currency.GOLD)).isEqualTo(50L)
    }

    @Test
    fun `all-of composite completes only when every leaf does`() {
        registry.register(
            CustomAdvancement(
                id = advId,
                titleKey = MessageKey("astera.advancement.demo.title"),
                descriptionKey = MessageKey("astera.advancement.demo.desc"),
                iconItemId = iconItem,
                criteria = AdvancementCriteria.AllOf(
                    listOf(
                        AdvancementCriteria.KillCount(2),
                        AdvancementCriteria.MatchesWon(1),
                    ),
                ),
                rewards = emptyList(),
            ),
        )

        // KillCount leaf reaches 2 — but MatchesWon is still 0, no completion.
        useCase.progress(player, advId, 1) // hits leaf 0 (kill count)
        val afterFirst = useCase.progress(player, advId, 1) // 2 kills now
        // The "first leaf in pre-order" rule means both calls go to leaf 0.
        assertThat((afterFirst as Result.Success).value.completedAt).isNull()
        assertThat(capturedCompleted).isEmpty()
    }

    @Test
    fun `any-of composite completes when first leaf does`() {
        val id = AdvancementId("any-target")
        registry.register(
            CustomAdvancement(
                id = id,
                titleKey = MessageKey("astera.advancement.any.title"),
                descriptionKey = MessageKey("astera.advancement.any.desc"),
                iconItemId = iconItem,
                criteria = AdvancementCriteria.AnyOf(
                    listOf(
                        AdvancementCriteria.KillCount(1),
                        AdvancementCriteria.MatchesWon(5),
                    ),
                ),
                rewards = listOf(AdvancementReward.GrantCurrency(Currency.STAR, 10)),
            ),
        )
        val r = useCase.progress(player, id, 1)
        assertThat((r as Result.Success).value.completedAt).isNotNull()
        assertThat(capturedCompleted).hasSize(1)
    }

    @Test
    fun `unknown advancement returns NotInCatalog`() {
        val r = useCase.progress(player, AdvancementId("nonexistent"), 1)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error).isInstanceOf(AdvancementError.NotInCatalog::class.java)
    }

    @Test
    fun `non-positive increment is rejected`() {
        val r = useCase.progress(player, advId, 0)
        assertThat(r).isInstanceOf(Result.Failure::class.java)
        assertThat((r as Result.Failure).error).isInstanceOf(AdvancementError.InvalidIncrement::class.java)
    }

    @Test
    fun `rewards fan out in declaration order`() {
        val id = AdvancementId("multi-reward")
        registry.register(
            CustomAdvancement(
                id = id,
                titleKey = MessageKey("astera.advancement.multi.title"),
                descriptionKey = MessageKey("astera.advancement.multi.desc"),
                iconItemId = iconItem,
                criteria = AdvancementCriteria.KillCount(1),
                rewards = listOf(
                    AdvancementReward.GrantCurrency(Currency.GOLD, 100),
                    AdvancementReward.GrantItem(ItemId("trophy"), 1),
                ),
            ),
        )
        useCase.progress(player, id, 1)
        assertThat(capturedRewards).hasSize(2)
        assertThat(capturedRewards[0].rewardIndex).isEqualTo(0)
        assertThat(capturedRewards[1].rewardIndex).isEqualTo(1)
        assertThat(capturedRewards[0].reward).isInstanceOf(AdvancementReward.GrantCurrency::class.java)
        assertThat(capturedRewards[1].reward).isInstanceOf(AdvancementReward.GrantItem::class.java)
    }

    @Test
    fun `completed snapshot excludes synthetic sub-row ids`() {
        registry.register(
            CustomAdvancement(
                id = advId,
                titleKey = MessageKey("astera.advancement.first_kill.title"),
                descriptionKey = MessageKey("astera.advancement.first_kill.desc"),
                iconItemId = iconItem,
                criteria = AdvancementCriteria.KillCount(1),
                rewards = emptyList(),
            ),
        )
        useCase.progress(player, advId, 1)
        // Completed set should include canonical id only; synthetic leaf ids are filtered.
        val done = useCase.completed(player)
        assertThat(done).containsExactly(advId)
    }

    /**
     * Minimal stub of [ICurrencyLedger] kept inside this test (no economy plugin dep).
     * Backed by the core-side [InMemoryPlayerAttributeContainer] so [Wallet]'s
     * facade lookup works.
     */
    private class StubLedger : ICurrencyLedger {
        private val containerStore = InMemoryPlayerAttributeContainer()
        private fun walletFor(playerId: PlayerId): Wallet =
            Wallet(containerStore.containerFor(playerId))
        override fun balance(playerId: PlayerId, currency: Currency): Long =
            walletFor(playerId).balance(currency)
        override fun wallet(playerId: PlayerId): Wallet = walletFor(playerId)
        override fun debit(
            playerId: PlayerId,
            currency: Currency,
            amount: Long,
        ): Result<Wallet, WalletError> = walletFor(playerId).withdraw(currency, amount)
        override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet =
            walletFor(playerId).deposit(currency, amount)
        override fun transfer(
            from: PlayerId,
            to: PlayerId,
            currency: Currency,
            amount: Long,
        ): Result<TransferResult, WalletError> {
            val fromWallet = walletFor(from)
            return when (val debited = fromWallet.withdraw(currency, amount)) {
                is Result.Success -> {
                    val toWallet = walletFor(to).deposit(currency, amount)
                    Result.success(TransferResult(fromWallet = fromWallet, toWallet = toWallet))
                }
                is Result.Failure -> Result.failure(debited.error)
            }
        }
    }
}

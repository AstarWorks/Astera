package com.astarworks.astera.core.application.usecase.quest

import com.astarworks.astera.core.application.contents.QuestChainContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.application.service.quest.InMemoryPlayerQuestProgressRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.advancement.AdvancementReward
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.event.AdvancementRewardGranted
import com.astarworks.astera.core.domain.event.QuestChainCompleted
import com.astarworks.astera.core.domain.event.QuestChainStarted
import com.astarworks.astera.core.domain.event.QuestStageCompleted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.QuestChain
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestError
import com.astarworks.astera.core.domain.quest.QuestStage
import com.astarworks.astera.core.domain.quest.QuestStageId
import com.astarworks.astera.core.domain.quest.QuestStageOutcome
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [ProgressQuestUseCase] + [StartQuestChainUseCase] (Wave 5s,
 * ADR-0091).
 *
 * Verifies:
 *  - startChain writes the entry stage and publishes [QuestChainStarted].
 *  - Repeat startChain on active row → [QuestError.AlreadyStarted].
 *  - Repeat startChain after completion → [QuestError.AlreadyCompleted].
 *  - progressCurrent on un-started chain → [QuestError.NotStarted].
 *  - Stage advance fires [QuestStageCompleted] + dispatches rewards.
 *  - Chain completion fires both stage + chain completed events and latches.
 *  - Idempotent re-progress after completion → [QuestError.AlreadyCompleted].
 *  - Composite `AllOf` uses the max-target leaf for HUD scalar.
 */
class ProgressQuestUseCaseTest {

    private val player = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
    private val chainId = QuestChainId("tutorial-chain")
    private val stage1 = QuestStageId("kill-monsters")
    private val stage2 = QuestStageId("win-match")

    private lateinit var registry: QuestChainContents
    private lateinit var repo: InMemoryPlayerQuestProgressRepository
    private lateinit var events: InMemoryDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var capturedStarted: MutableList<QuestChainStarted>
    private lateinit var capturedStage: MutableList<QuestStageCompleted>
    private lateinit var capturedChain: MutableList<QuestChainCompleted>
    private lateinit var capturedRewards: MutableList<AdvancementRewardGranted>
    private lateinit var ledger: StubLedger
    private lateinit var useCase: ProgressQuestUseCase

    @BeforeEach
    fun setUp() {
        registry = QuestChainContents()
        repo = InMemoryPlayerQuestProgressRepository()
        events = InMemoryDomainEventBus()
        scheduler = FakeScheduler(startTick = 200L)
        ledger = StubLedger()
        capturedStarted = mutableListOf()
        capturedStage = mutableListOf()
        capturedChain = mutableListOf()
        capturedRewards = mutableListOf()
        events.subscribe(QuestChainStarted::class) { capturedStarted.add(it) }
        events.subscribe(QuestStageCompleted::class) { capturedStage.add(it) }
        events.subscribe(QuestChainCompleted::class) { capturedChain.add(it) }
        events.subscribe(AdvancementRewardGranted::class) { capturedRewards.add(it) }
        val grant = GrantQuestRewardUseCase({ ledger }, events, scheduler)
        val start = StartQuestChainUseCase(registry, repo, events, scheduler)
        useCase = ProgressQuestUseCase(
            registry = registry,
            repository = repo,
            rewardGrant = grant,
            startChainUseCase = start,
            events = events,
            scheduler = scheduler,
        )
    }

    @Test
    fun `startChain writes entry stage and publishes QuestChainStarted`() {
        registry.register(twoStageChain())
        val outcome = useCase.startChain(player, chainId)
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val progress = (outcome as Result.Success).value
        assertThat(progress.currentStageId).isEqualTo(stage1)
        assertThat(progress.stageProgress).isZero()
        assertThat(progress.completedAt).isNull()
        assertThat(capturedStarted).hasSize(1)
        assertThat(capturedStarted.first().entryStageId).isEqualTo(stage1)
    }

    @Test
    fun `startChain twice returns AlreadyStarted`() {
        registry.register(twoStageChain())
        useCase.startChain(player, chainId)
        val outcome = useCase.startChain(player, chainId)
        assertThat((outcome as Result.Failure).error).isInstanceOf(QuestError.AlreadyStarted::class.java)
    }

    @Test
    fun `progressCurrent on un-started chain returns NotStarted`() {
        registry.register(twoStageChain())
        val outcome = useCase.progressCurrent(player, chainId, 1)
        assertThat((outcome as Result.Failure).error).isInstanceOf(QuestError.NotStarted::class.java)
    }

    @Test
    fun `progressCurrent emits StillInProgress when target not reached`() {
        registry.register(twoStageChain())
        useCase.startChain(player, chainId)
        val outcome = useCase.progressCurrent(player, chainId, 1)
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat((outcome as Result.Success).value).isInstanceOf(QuestStageOutcome.StillInProgress::class.java)
        assertThat(capturedStage).isEmpty()
    }

    @Test
    fun `progressCurrent on stage target fires StageCompleted and advances`() {
        registry.register(twoStageChain())
        useCase.startChain(player, chainId)
        useCase.progressCurrent(player, chainId, 1)
        useCase.progressCurrent(player, chainId, 1)
        val outcome = useCase.progressCurrent(player, chainId, 1) // target reached (3)
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val staged = (outcome as Result.Success).value
        assertThat(staged).isInstanceOf(QuestStageOutcome.StageAdvanced::class.java)
        val advanced = staged as QuestStageOutcome.StageAdvanced
        assertThat(advanced.completed.id).isEqualTo(stage1)
        assertThat(advanced.next.id).isEqualTo(stage2)
        assertThat(advanced.progress.currentStageId).isEqualTo(stage2)
        assertThat(advanced.progress.stageProgress).isZero()

        assertThat(capturedStage).hasSize(1)
        assertThat(capturedStage.first().completedStageId).isEqualTo(stage1)
        assertThat(capturedStage.first().nextStageId).isEqualTo(stage2)
        // Rewards (currency 50) granted synchronously through the ledger and an event fanned out.
        assertThat(ledger.creditedAmount(Currency.GOLD)).isEqualTo(50L)
        assertThat(capturedRewards).hasSize(1)
        assertThat(capturedRewards.first().advancementId.value).isEqualTo("quest-tutorial-chain-kill-monsters")
    }

    @Test
    fun `clearing terminal stage fires both StageCompleted and ChainCompleted`() {
        registry.register(twoStageChain())
        useCase.startChain(player, chainId)
        // Clear stage1 (KillCount 3)
        repeat(3) { useCase.progressCurrent(player, chainId, 1) }
        capturedStage.clear()
        capturedRewards.clear()
        // Clear stage2 (MatchesWon 1)
        val outcome = useCase.progressCurrent(player, chainId, 1)
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val final = (outcome as Result.Success).value
        assertThat(final).isInstanceOf(QuestStageOutcome.ChainCompleted::class.java)
        val cc = final as QuestStageOutcome.ChainCompleted
        assertThat(cc.completed.id).isEqualTo(stage2)
        assertThat(cc.progress.isComplete).isTrue()

        assertThat(capturedStage).hasSize(1)
        assertThat(capturedStage.first().nextStageId).isNull()
        assertThat(capturedChain).hasSize(1)
        assertThat(capturedChain.first().chainId).isEqualTo(chainId)
        assertThat(capturedRewards).hasSize(1)
        assertThat(capturedRewards.first().reward).isInstanceOf(AdvancementReward.GrantTitle::class.java)
    }

    @Test
    fun `progress after completion is idempotent`() {
        registry.register(twoStageChain())
        useCase.startChain(player, chainId)
        repeat(3) { useCase.progressCurrent(player, chainId, 1) } // clear stage1
        useCase.progressCurrent(player, chainId, 1) // clear stage2 → chain done
        val outcome = useCase.progressCurrent(player, chainId, 1)
        assertThat((outcome as Result.Failure).error).isInstanceOf(QuestError.AlreadyCompleted::class.java)
    }

    @Test
    fun `startChain after completion returns AlreadyCompleted`() {
        registry.register(twoStageChain())
        useCase.startChain(player, chainId)
        repeat(3) { useCase.progressCurrent(player, chainId, 1) }
        useCase.progressCurrent(player, chainId, 1)
        val outcome = useCase.startChain(player, chainId)
        assertThat((outcome as Result.Failure).error).isInstanceOf(QuestError.AlreadyCompleted::class.java)
    }

    @Test
    fun `unknown chain returns UnknownChain`() {
        val outcome = useCase.startChain(player, QuestChainId("nope"))
        assertThat((outcome as Result.Failure).error).isInstanceOf(QuestError.UnknownChain::class.java)
    }

    @Test
    fun `invalid increment returns InvalidIncrement`() {
        registry.register(twoStageChain())
        useCase.startChain(player, chainId)
        val outcome = useCase.progressCurrent(player, chainId, 0)
        assertThat((outcome as Result.Failure).error).isInstanceOf(QuestError.InvalidIncrement::class.java)
    }

    @Test
    fun `AllOf composite uses max-target leaf for HUD scalar`() {
        val chain = QuestChain(
            id = QuestChainId("compound"),
            titleKey = MessageKey("astera.quest.compound.title"),
            stages = listOf(
                QuestStage(
                    id = QuestStageId("a"),
                    titleKey = MessageKey("astera.quest.compound.a.title"),
                    descriptionKey = MessageKey("astera.quest.compound.a.desc"),
                    criteria = AdvancementCriteria.AllOf(
                        listOf(AdvancementCriteria.KillCount(3), AdvancementCriteria.MatchesWon(5)),
                    ),
                ),
            ),
        )
        registry.register(chain)
        useCase.startChain(player, QuestChainId("compound"))
        // Increment 4: still under max-target (5), so still in progress.
        val r1 = useCase.progressCurrent(player, QuestChainId("compound"), 4)
        assertThat((r1 as Result.Success).value).isInstanceOf(QuestStageOutcome.StillInProgress::class.java)
        // Increment +1: 5 → reaches the max target → chain completes (no following stage).
        val r2 = useCase.progressCurrent(player, QuestChainId("compound"), 1)
        assertThat((r2 as Result.Success).value).isInstanceOf(QuestStageOutcome.ChainCompleted::class.java)
    }

    private fun twoStageChain(): QuestChain = QuestChain(
        id = chainId,
        titleKey = MessageKey("astera.quest.tutorial-chain.title"),
        stages = listOf(
            QuestStage(
                id = stage1,
                titleKey = MessageKey("astera.quest.tutorial-chain.kill-monsters.title"),
                descriptionKey = MessageKey("astera.quest.tutorial-chain.kill-monsters.desc"),
                criteria = AdvancementCriteria.KillCount(3),
                rewards = listOf(AdvancementReward.GrantCurrency(Currency.GOLD, 50)),
                followingStageId = stage2,
            ),
            QuestStage(
                id = stage2,
                titleKey = MessageKey("astera.quest.tutorial-chain.win-match.title"),
                descriptionKey = MessageKey("astera.quest.tutorial-chain.win-match.desc"),
                criteria = AdvancementCriteria.MatchesWon(1),
                rewards = listOf(AdvancementReward.GrantTitle(com.astarworks.astera.core.domain.achievement.TitleId("rookie"))),
            ),
        ),
    )

    private class StubLedger : ICurrencyLedger {
        private val store = InMemoryPlayerAttributeContainer()
        private val credited = mutableMapOf<Currency, Long>()
        fun creditedAmount(c: Currency): Long = credited[c] ?: 0L
        private fun walletFor(p: PlayerId) = Wallet(store.containerFor(p))
        override fun balance(playerId: PlayerId, currency: Currency): Long =
            walletFor(playerId).balance(currency)
        override fun wallet(playerId: PlayerId): Wallet = walletFor(playerId)
        override fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError> =
            walletFor(playerId).withdraw(currency, amount)
        override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet {
            credited[currency] = (credited[currency] ?: 0L) + amount
            return walletFor(playerId).deposit(currency, amount)
        }
        override fun transfer(
            from: PlayerId, to: PlayerId, currency: Currency, amount: Long,
        ): Result<TransferResult, WalletError> {
            val a = walletFor(from)
            return when (val w = a.withdraw(currency, amount)) {
                is Result.Success -> Result.success(
                    TransferResult(fromWallet = a, toWallet = walletFor(to).deposit(currency, amount)),
                )
                is Result.Failure -> Result.failure(w.error)
            }
        }
    }
}

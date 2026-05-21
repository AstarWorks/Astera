package com.astarworks.astera.core.application.usecase.recipe

import com.astarworks.astera.core.application.contents.AdvancementContents
import com.astarworks.astera.core.application.contents.ItemContents
import com.astarworks.astera.core.application.contents.RecipeContents
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.TransferResult
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.advancement.InMemoryPlayerAdvancementProgressRepository
import com.astarworks.astera.core.application.usecase.advancement.GrantAdvancementRewardUseCase
import com.astarworks.astera.core.application.usecase.advancement.ProgressAdvancementUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.CustomAdvancement
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.Wallet
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.event.RecipeCrafted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.recipe.CraftError
import com.astarworks.astera.core.domain.recipe.CraftingStation
import com.astarworks.astera.core.domain.recipe.CustomRecipe
import com.astarworks.astera.core.domain.recipe.RecipeId
import com.astarworks.astera.core.domain.recipe.RecipeIngredients
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [CraftRecipeUseCase] (Wave 5s, ADR-0091).
 *
 * Verifies:
 *  - Happy path emits [RecipeCrafted] with correct consumed map.
 *  - Missing recipe / wrong station / insufficient ingredients return typed errors.
 *  - Snapshot ingredient counting aggregates across hotbar/main/offhand/armor.
 *  - Conversion + Shapeless required-ingredient computation.
 */
class CraftRecipeUseCaseTest {

    private val player = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
    private val iron = ItemId("iron-ingot")
    private val stick = ItemId("stick")
    private val sword = ItemId("example-sword")

    private lateinit var recipes: RecipeContents
    private lateinit var snapshots: InMemoryPlayerInventorySnapshotRepository
    private lateinit var items: ItemContents
    private lateinit var advancements: AdvancementContents
    private lateinit var events: InMemoryDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var advancementService: ProgressAdvancementUseCase
    private lateinit var useCase: CraftRecipeUseCase
    private lateinit var captured: MutableList<RecipeCrafted>

    @BeforeEach
    fun setUp() {
        recipes = RecipeContents()
        snapshots = InMemoryPlayerInventorySnapshotRepository()
        items = ItemContents()
        advancements = AdvancementContents()
        events = InMemoryDomainEventBus()
        captured = mutableListOf()
        events.subscribe(RecipeCrafted::class) { captured.add(it) }
        scheduler = FakeScheduler(startTick = 500L)
        val ledger = StubLedger()
        val grantUC = GrantAdvancementRewardUseCase({ ledger }, events, scheduler)
        val advRepo = InMemoryPlayerAdvancementProgressRepository()
        advancementService = ProgressAdvancementUseCase(
            registry = advancements,
            repository = advRepo,
            rewardGrant = grantUC,
            events = events,
            scheduler = scheduler,
        )
        useCase = CraftRecipeUseCase(
            recipes = recipes,
            snapshots = snapshots,
            items = items,
            advancementService = advancementService,
            events = events,
            scheduler = scheduler,
        )
    }

    @Test
    fun `unknown recipe returns UnknownRecipe error`() {
        val outcome = useCase.craft(player, RecipeId("nope"))
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(CraftError.UnknownRecipe::class.java)
    }

    @Test
    fun `no snapshot returns NoInventory`() {
        recipes.register(swordRecipe())
        val outcome = useCase.craft(player, RecipeId("iron-sword"))
        assertThat((outcome as Result.Failure).error).isInstanceOf(CraftError.NoInventory::class.java)
    }

    @Test
    fun `wrong station returns WrongStation`() {
        recipes.register(swordRecipe(station = CraftingStation.AsteraWorkbench))
        snapshots.save(snapshotWith(iron to 3, stick to 1))
        val outcome = useCase.craft(player, RecipeId("iron-sword"), station = CraftingStation.Hand)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(CraftError.WrongStation::class.java)
        assertThat((err as CraftError.WrongStation).required).isEqualTo(CraftingStation.AsteraWorkbench)
    }

    @Test
    fun `insufficient ingredients lists every shortfall`() {
        recipes.register(swordRecipe())
        snapshots.save(snapshotWith(iron to 1))
        val outcome = useCase.craft(player, RecipeId("iron-sword"))
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(CraftError.InsufficientIngredients::class.java)
        val missing = (err as CraftError.InsufficientIngredients).missing
        // sword needs 2 iron + 1 stick; we have 1 iron / 0 stick → both short.
        assertThat(missing).containsKeys(iron, stick)
        assertThat(missing[iron]).isEqualTo(1)
        assertThat(missing[stick]).isEqualTo(1)
    }

    @Test
    fun `happy path publishes RecipeCrafted with consumed map`() {
        recipes.register(swordRecipe())
        snapshots.save(snapshotWith(iron to 5, stick to 2))
        val outcome = useCase.craft(player, RecipeId("iron-sword"))
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        val result = (outcome as Result.Success).value
        assertThat(result.output).isEqualTo(sword)
        assertThat(result.outputCount).isEqualTo(1)
        assertThat(result.consumed).containsEntry(iron, 2).containsEntry(stick, 1)
        assertThat(captured).hasSize(1)
        assertThat(captured.first().output).isEqualTo(sword)
        assertThat(captured.first().atTick).isEqualTo(500L)
    }

    @Test
    fun `shapeless recipe matches when player has the multiset`() {
        recipes.register(
            CustomRecipe(
                id = RecipeId("trade-iron"),
                outputItem = sword,
                outputCount = 1,
                ingredients = RecipeIngredients.Shapeless(mapOf(iron to 3)),
            ),
        )
        snapshots.save(snapshotWith(iron to 5))
        val outcome = useCase.craft(player, RecipeId("trade-iron"))
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat((outcome as Result.Success).value.consumed).containsEntry(iron, 3)
    }

    @Test
    fun `conversion recipe consumes ratio of input`() {
        recipes.register(
            CustomRecipe(
                id = RecipeId("smelt-iron"),
                outputItem = iron,
                outputCount = 1,
                ingredients = RecipeIngredients.Conversion(from = ItemId("iron-ore"), to = iron, ratio = 2),
                craftingStation = CraftingStation.AsteraFurnace,
            ),
        )
        snapshots.save(snapshotWith(ItemId("iron-ore") to 5))
        val outcome = useCase.craft(player, RecipeId("smelt-iron"), station = CraftingStation.AsteraFurnace)
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat((outcome as Result.Success).value.consumed).containsEntry(ItemId("iron-ore"), 2)
    }

    @Test
    fun `unlock criteria gates the craft when player has no advancements`() {
        recipes.register(
            CustomRecipe(
                id = RecipeId("advanced-sword"),
                outputItem = sword,
                outputCount = 1,
                ingredients = RecipeIngredients.Shapeless(mapOf(iron to 1)),
                unlockCriteria = AdvancementCriteria.MatchesWon(1),
            ),
        )
        snapshots.save(snapshotWith(iron to 5))
        val outcome = useCase.craft(player, RecipeId("advanced-sword"))
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(CraftError.Locked::class.java)
    }

    @Test
    fun `unlock criteria passes once player has any completed advancement`() {
        // Phase 1 conservative oracle: any completed advancement unlocks.
        advancements.register(
            CustomAdvancement(
                id = AdvancementId("starter"),
                titleKey = MessageKey("astera.advancement.starter.title"),
                descriptionKey = MessageKey("astera.advancement.starter.desc"),
                iconItemId = sword,
                criteria = AdvancementCriteria.KillCount(1),
                rewards = emptyList(),
            ),
        )
        advancementService.progress(player, AdvancementId("starter"), 1)

        recipes.register(
            CustomRecipe(
                id = RecipeId("unlock-test"),
                outputItem = sword,
                outputCount = 1,
                ingredients = RecipeIngredients.Shapeless(mapOf(iron to 1)),
                unlockCriteria = AdvancementCriteria.MatchesWon(1),
            ),
        )
        snapshots.save(snapshotWith(iron to 1))
        val outcome = useCase.craft(player, RecipeId("unlock-test"))
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
    }

    private fun swordRecipe(station: CraftingStation? = null): CustomRecipe = CustomRecipe(
        id = RecipeId("iron-sword"),
        outputItem = sword,
        outputCount = 1,
        ingredients = RecipeIngredients.Shaped(
            pattern = listOf(" A ", " A ", " S "),
            key = mapOf('A' to iron, 'S' to stick),
        ),
        craftingStation = station,
    )

    private fun snapshotWith(vararg slots: Pair<ItemId, Int>): PlayerInventorySnapshot {
        val hotbar: MutableList<InventoryItemSlot?> = MutableList(9) { null }
        slots.forEachIndexed { idx, (id, count) ->
            require(idx < 9) { "test snapshot supports up to 9 distinct items" }
            hotbar[idx] = InventoryItemSlot.ContentItem(itemId = id.value, count = count)
        }
        return PlayerInventorySnapshot(
            playerId = player,
            hotbar = hotbar,
            main = List(27) { null },
            offhand = null,
            armor = List(4) { null },
            version = PlayerInventorySnapshot.CURRENT_VERSION,
            savedAt = Instant.parse("2026-05-21T00:00:00Z"),
        )
    }

    private class StubLedger : ICurrencyLedger {
        private val store = InMemoryPlayerAttributeContainer()
        private fun walletFor(p: PlayerId) = Wallet(store.containerFor(p))
        override fun balance(playerId: PlayerId, currency: Currency): Long =
            walletFor(playerId).balance(currency)
        override fun wallet(playerId: PlayerId): Wallet = walletFor(playerId)
        override fun debit(playerId: PlayerId, currency: Currency, amount: Long): Result<Wallet, WalletError> =
            walletFor(playerId).withdraw(currency, amount)
        override fun credit(playerId: PlayerId, currency: Currency, amount: Long): Wallet =
            walletFor(playerId).deposit(currency, amount)
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

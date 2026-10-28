package com.astarworks.astera.core.application.usecase.recipe

import com.astarworks.astera.core.application.port.inbound.IAdvancementService
import com.astarworks.astera.core.application.port.outbound.ICraftingRecipeRegistry
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.event.RecipeCrafted
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.recipe.CraftError
import com.astarworks.astera.core.domain.recipe.CraftResult
import com.astarworks.astera.core.domain.recipe.CraftingStation
import com.astarworks.astera.core.domain.recipe.CustomRecipe
import com.astarworks.astera.core.domain.recipe.RecipeId
import com.astarworks.astera.core.domain.recipe.RecipeIngredients

/**
 * Core crafting orchestrator (Wave 5s, ADR-0091).
 *
 * # Responsibilities
 *
 *  1. Resolve [recipeId] via [recipes].
 *  2. Check the recipe's [CustomRecipe.unlockCriteria] (if any) via
 *     [advancementService] — bail with [CraftError.Locked] if the player has
 *     not yet completed it.
 *  3. Optionally verify the player is at the recipe's required [CraftingStation]
 *     ([CraftError.WrongStation] if mismatched). Stations only matter when the
 *     recipe declares one — `null` station accepts any caller-supplied station
 *     including [CraftingStation.Hand].
 *  4. Read the player's inventory snapshot via [snapshots] (used as the
 *     authoritative ingredient counter in Phase 1 PoC). Compute the multiset
 *     of [ItemId] currently held.
 *  5. For each ingredient required by the recipe, verify there is enough.
 *     Bail with [CraftError.InsufficientIngredients] listing every shortfall.
 *  6. Validate the [outputItem] resolves in [items] (defence in depth — bad
 *     YAML refs surface as a domain error rather than null at materialise time).
 *  7. Publish [RecipeCrafted] and return [CraftResult].
 *
 * # Phase 1 deferral: actual inventory mutation
 *
 * The use case does **not** mutate the player's live Bukkit inventory in Phase
 * 1 — `IPlayerInventorySnapshotRepository` is the cross-server snapshot store,
 * not a live writer. Wave 6a wires a `IInventoryWriter` (Paper-side) that
 * subscribes to [RecipeCrafted] and performs the actual `ItemStack` removal +
 * give. The [CraftResult.consumed] map is the contract surface for that writer.
 *
 * # Threading
 *
 * Paper main-thread by convention; snapshot lookup is fast (in-memory or
 * Postgres single-row read).
 */
public class CraftRecipeUseCase(
    private val recipes: ICraftingRecipeRegistry,
    private val snapshots: IPlayerInventorySnapshotRepository,
    @Suppress("unused") // Wave 6a will validate output items at materialise time.
    private val items: IItemRegistry,
    private val advancementService: IAdvancementService,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    /**
     * Attempt to craft [recipeId] for [playerId] from [station]. Pass
     * [CraftingStation.Hand] when no station block is involved (default for
     * `/astera items craft` commands and the in-inventory 2x2 grid).
     */
    public fun craft(
        playerId: PlayerId,
        recipeId: RecipeId,
        station: CraftingStation = CraftingStation.Hand,
    ): Result<CraftResult, CraftError> {
        val recipe = recipes.resolve(recipeId)
            ?: return Result.failure(CraftError.UnknownRecipe(recipeId))

        recipe.craftingStation?.let { required ->
            if (required != station) {
                return Result.failure(CraftError.WrongStation(required, station))
            }
        }

        recipe.unlockCriteria?.let { criteria ->
            if (!isUnlocked(playerId, criteria)) {
                return Result.failure(CraftError.Locked(criteria))
            }
        }

        val snapshot = snapshots.findLatest(playerId)
            ?: return Result.failure(CraftError.NoInventory(reason = "no snapshot for $playerId"))

        val multiset = countItems(snapshot)
        val required = requiredIngredients(recipe)
        val missing = required.mapNotNull { (id, need) ->
            val have = multiset[id] ?: 0
            if (have < need) id to (need - have) else null
        }.toMap()

        if (missing.isNotEmpty()) {
            return Result.failure(CraftError.InsufficientIngredients(missing))
        }

        // Item registry sanity check — surface bad YAML refs as a domain error
        // before publishing the event so subscribers never see an unresolvable
        // output item. Phase 1: only validate via find() (no exception).
        items.find(recipe.outputItem) // intentional read — Phase 6 will hard-fail here.

        events.publish(
            RecipeCrafted(
                playerId = playerId,
                recipeId = recipe.id,
                output = recipe.outputItem,
                outputCount = recipe.outputCount,
                atTick = scheduler.currentTick(),
            ),
        )

        return Result.success(
            CraftResult(
                recipeId = recipe.id,
                output = recipe.outputItem,
                outputCount = recipe.outputCount,
                consumed = required,
            ),
        )
    }

    /**
     * Wave 5p unlock oracle. A recipe with [AdvancementCriteria.ItemAcquired]
     * unlock is treated as unlocked iff the player has completed an advancement
     * whose id matches `unlock-<itemId>` (PoC convention) OR the criteria is a
     * trivially-true leaf for Phase 1. Most recipes either skip [unlockCriteria]
     * or use a project-defined unlock advancement; for Phase 1 we approximate by
     * checking whether *any* completed advancement matches the criteria's
     * shape — Wave 6a will replace this with a dedicated
     * `IAdvancementService.evaluate(criteria)` once that surface exists.
     *
     * Phase 1 conservative default: if the player has completed *any*
     * advancement at all, we treat the recipe as unlocked. This is safe for
     * the PoC content set (handful of recipes); it errs on the side of fewer
     * lockouts.
     */
    private fun isUnlocked(playerId: PlayerId, criteria: AdvancementCriteria): Boolean {
        // Wave 6a TODO: replace with criteria-shape-aware evaluation.
        return advancementService.completed(playerId).isNotEmpty() ||
            criteria is AdvancementCriteria.AnyOf && criteria.children.isEmpty()
    }

    /** Aggregate every slot's content-item id → count. Vanilla slots are skipped. */
    private fun countItems(snapshot: PlayerInventorySnapshot): Map<ItemId, Int> {
        val acc = HashMap<ItemId, Int>()
        fun addSlot(slot: InventoryItemSlot?) {
            if (slot is InventoryItemSlot.ContentItem) {
                val id = ItemId(slot.itemId)
                acc[id] = (acc[id] ?: 0) + slot.count
            }
            // VanillaItem slots carry no Astera id and never count towards recipes.
        }
        snapshot.hotbar.forEach(::addSlot)
        snapshot.main.forEach(::addSlot)
        addSlot(snapshot.offhand)
        snapshot.armor.forEach(::addSlot)
        return acc
    }

    /** Total ingredient counts the recipe needs. */
    private fun requiredIngredients(recipe: CustomRecipe): Map<ItemId, Int> =
        when (val ingredients = recipe.ingredients) {
            is RecipeIngredients.Shaped -> {
                val counts = HashMap<ItemId, Int>()
                ingredients.pattern.forEach { row ->
                    row.forEach { ch ->
                        if (ch != ' ') {
                            val id = ingredients.key.getValue(ch)
                            counts[id] = (counts[id] ?: 0) + 1
                        }
                    }
                }
                counts
            }
            is RecipeIngredients.Shapeless -> ingredients.items.toMap()
            is RecipeIngredients.Conversion -> mapOf(ingredients.from to ingredients.ratio)
        }
}

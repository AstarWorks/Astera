package com.astarworks.astera.core.domain.recipe

import com.astarworks.astera.core.domain.item.ItemId

/**
 * Outcome of a successful [com.astarworks.astera.core.application.usecase.recipe.CraftRecipeUseCase.craft]
 * call (Wave 5s, ADR-0091).
 *
 * @property recipeId The recipe that was matched.
 * @property output The item that was produced.
 * @property outputCount How many of [output] were produced.
 * @property consumed The actual ingredients deducted from the player's
 *  inventory snapshot (Phase 1 PoC — the use case computes this for
 *  audit + event payloads; the actual side-effect of removing items from
 *  the player's live inventory ships in Wave 6a via `IInventoryWriter`).
 */
public data class CraftResult(
    public val recipeId: RecipeId,
    public val output: ItemId,
    public val outputCount: Int,
    public val consumed: Map<ItemId, Int>,
)

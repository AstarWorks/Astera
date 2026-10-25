package com.astarworks.astera.core.domain.recipe

import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Astera's vanilla-recipe replacement (Wave 5s, ADR-0091).
 *
 * # Why replace Vanilla
 *
 * Vanilla Minecraft recipes ship as datapack JSON consumed by Mojang's
 * `RecipeManager` with vanilla-only `ItemStack` semantics. Astera needs:
 *
 *  - **Custom outputs**. The output side is an Astera [ItemId] (resolved via
 *    `IItemRegistry` to an `ItemSpec` at materialisation time), not a raw
 *    Vanilla material. That gives us i18n, attributes, model overrides, skill
 *    bindings for free (ADR-0073).
 *  - **Conditional unlock**. Recipes can gate behind an [AdvancementCriteria]
 *    so progression actually drives what the player can craft. Wave 5p's
 *    `IAdvancementService.completed()` is the unlock oracle.
 *  - **Custom stations**. [CraftingStation] decouples the recipe from the
 *    Vanilla 2x2 / 3x3 / furnace layouts. Astera's own surfaces ship in
 *    Wave 5h GUI + Wave 6a workbench.
 *  - **Cross-server consistency**. Recipes live in YAML files under
 *    `content/recipes/` and reload via `/astera reload recipes` (ADR-0065). All
 *    cluster pods see the same catalog after a deploy.
 *
 * # Wire to the rest of the system
 *
 *  1. Loader: YAML in `content/recipes/` parsed into [CustomRecipe] via
 *     [com.astarworks.astera.core.application.config.recipe.RecipeYamlConfig].
 *  2. Registry: [com.astarworks.astera.core.application.port.outbound.ICraftingRecipeRegistry]
 *     (Contents-backed) holds the catalog and is reloadable.
 *  3. Use case: [com.astarworks.astera.core.application.usecase.recipe.CraftRecipeUseCase]
 *     resolves a recipe, checks [unlockCriteria] via `IAdvancementService`,
 *     verifies the player's inventory, and publishes
 *     [com.astarworks.astera.core.domain.event.RecipeCrafted].
 *
 * @property outputItem The crafted item id; resolved to an `ItemSpec` via
 *  `IItemRegistry` at materialise time.
 * @property outputCount How many of [outputItem] this recipe produces per
 *  crafting action. Must be >= 1.
 * @property ingredients The input topology (shaped grid / shapeless multiset /
 *  1-for-1 conversion).
 * @property craftingStation Required station; `null` allows any station
 *  (including Hand). Phase 1: Hand is the default if unset.
 * @property unlockCriteria Optional progression gate. Recipes with non-null
 *  criteria are hidden from the recipe selector until the player has
 *  completed them — evaluated via `IAdvancementService.completed()` at the
 *  use-case layer (the domain stays pure).
 */
public data class CustomRecipe(
    public val id: RecipeId,
    public val outputItem: ItemId,
    public val outputCount: Int,
    public val ingredients: RecipeIngredients,
    public val craftingStation: CraftingStation? = null,
    public val unlockCriteria: AdvancementCriteria? = null,
) {
    init {
        require(outputCount > 0) { "CustomRecipe.outputCount must be > 0, got $outputCount" }
    }
}

package com.astarworks.astera.core.application.contents

import com.astarworks.astera.core.application.config.recipe.RecipeYamlConfig
import com.astarworks.astera.core.application.port.outbound.ICraftingRecipeRegistry
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.recipe.CustomRecipe
import com.astarworks.astera.core.domain.recipe.RecipeId
import com.astarworks.astera.core.domain.recipe.RecipeIngredients

/**
 * Recipe-side `Contents<RecipeId, CustomRecipe>` (Wave 5s, ADR-0091,
 * ADR-0060 substrate).
 *
 * Bootstrap directory: `content/recipes/`. Inherits the substrate's atomic
 * map + reload semantics — see [AdvancementContents] for the canonical shape
 * (Wave 5p).
 *
 * Implements [ICraftingRecipeRegistry] (the marker port the use cases consume)
 * so `services.register<ICraftingRecipeRegistry>(recipeContents, priority = 0)`
 * in `AsteraCorePlugin` exposes this instance to the rest of the codebase.
 *
 * # findMatching
 *
 * Phase 1 implementation: linear scan over [all] — acceptable for Phase 1 PoC
 * where the recipe catalog is small (~tens of entries). Shaped recipes are
 * intentionally skipped (positional matching is a Wave 6a workbench-GUI
 * concern); shapeless + conversion variants are matched by multiset subset.
 */
public class RecipeContents : YamlDynamicContents<RecipeId, CustomRecipe, RecipeYamlConfig>(
    categoryName = "recipes",
    deserializer = RecipeYamlConfig.serializer(),
),
    ICraftingRecipeRegistry {

    override fun toEntry(config: RecipeYamlConfig): Pair<RecipeId, CustomRecipe> {
        val recipe = config.toDomain()
        return recipe.id to recipe
    }

    /** Convenience overload: register by recipe alone (it owns its id). */
    public fun register(recipe: CustomRecipe) {
        register(recipe.id, recipe)
    }

    override fun findMatching(input: List<ItemId>): CustomRecipe? {
        if (input.isEmpty()) return null
        val multiset: Map<ItemId, Int> = input.groupingBy { it }.eachCount()
        return all().firstOrNull { recipe -> matches(recipe.ingredients, multiset, input) }
    }

    private fun matches(
        ingredients: RecipeIngredients,
        multiset: Map<ItemId, Int>,
        rawInput: List<ItemId>,
    ): Boolean = when (ingredients) {
        is RecipeIngredients.Shapeless -> ingredients.items.all { (itemId, requiredCount) ->
            (multiset[itemId] ?: 0) >= requiredCount
        }
        is RecipeIngredients.Conversion -> rawInput.firstOrNull() == ingredients.from && rawInput.size == 1
        is RecipeIngredients.Shaped -> false // positional matching not supported in Phase 1.
    }
}

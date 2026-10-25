package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.application.contents.Contents
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.recipe.CustomRecipe
import com.astarworks.astera.core.domain.recipe.RecipeId

/**
 * Read-only catalog of [CustomRecipe] templates (Wave 5s, ADR-0091).
 *
 * Populated at startup by `RecipeContents.bootstrap(content/recipes/)` and
 * hot-reloadable via `/astera reload recipes` (ADR-0065 dispatcher). Mirrors
 * [IAdvancementRegistry] / [IItemRegistry] / [ITutorialRegistry] — the marker
 * extends [Contents] so the underlying substrate (YamlDynamicContents) provides
 * `find` / `all` / `bootstrap` / `reload` uniformly.
 *
 * Phase 4 (UGC marketplace) may swap to a DB-backed `Contents<RecipeId,
 * CustomRecipe>` implementation without touching any use case.
 *
 * # Reverse-index methods
 *
 * The two extra methods ([resolve], [findMatching]) are convenience helpers
 * for the crafting use case. They are kept here (rather than as extension
 * functions on `Contents<…>`) so the persistence overlay can short-circuit
 * `findMatching` via a SQL index in Phase 4 instead of scanning every recipe.
 */
public interface ICraftingRecipeRegistry : Contents<RecipeId, CustomRecipe> {

    /** Alias for [find]; named for symmetry with use-case code that talks of "resolving" a recipe. */
    public fun resolve(id: RecipeId): CustomRecipe? = find(id)

    /**
     * Find a recipe whose ingredients match the supplied [input] multiset.
     *
     * Phase 1 PoC: scans [Contents.all] and returns the first recipe whose
     * `RecipeIngredients.Shapeless` items are a subset of [input]'s multiset,
     * or whose `Conversion.from` appears in [input]. Shaped recipes are
     * **not** matched here (positional matching requires a 2D grid input,
     * which is a Wave 6a workbench-GUI concern).
     *
     * Returns `null` if no recipe matches. Phase 4 may swap to a hashed
     * multiset index.
     */
    public fun findMatching(input: List<ItemId>): CustomRecipe?
}

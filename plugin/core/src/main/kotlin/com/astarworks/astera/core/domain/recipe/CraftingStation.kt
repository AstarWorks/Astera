package com.astarworks.astera.core.domain.recipe

/**
 * The crafting surface a [CustomRecipe] must be executed at (Wave 5s, ADR-0091).
 *
 * Astera completely replaces Vanilla's recipe matching (which routes through
 * the Mojang `RecipeManager` / NBT round-trip). Stations here are Astera's own
 * vocabulary — Paper-side adapters (Wave 6) bind each enum value to a block
 * material + GUI surface (`AsteraWorkbench` → Wave 5h Confirmation GUI etc.).
 *
 *  - [Hand] — 2x2 player inventory grid equivalent; no station block required.
 *  - [AsteraWorkbench] — Astera's 3x3 + ingredient extras workbench (open via
 *    NPC interaction or block click; Wave 5l NPC interaction router routes to
 *    `IntentOpenGui(astera.workbench)`).
 *  - [AsteraFurnace] — smelt-style 1-input → 1-output recipes (typically
 *    `RecipeIngredients.Conversion`); fuel is conceptual at Phase 1 (no
 *    burn-time tracking yet; revisit if Phase 3 economy demands it).
 *  - [AsteraEnchantTable] — single-item augmentation (e.g. add `+sharpness` via
 *    `RecipeIngredients.Conversion` from input item + reagent).
 *
 * Phase 1 Wave 5s only validates the station tag against
 * `CraftRecipeUseCase`'s caller-supplied context; UI to actually present the
 * different surfaces ships in Wave 6a (HUD + workbench inventory GUI).
 */
public enum class CraftingStation {
    Hand,
    AsteraWorkbench,
    AsteraFurnace,
    AsteraEnchantTable,
}

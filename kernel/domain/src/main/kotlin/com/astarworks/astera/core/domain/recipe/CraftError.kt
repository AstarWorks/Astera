package com.astarworks.astera.core.domain.recipe

import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Expected failure modes of [com.astarworks.astera.core.application.usecase.recipe.CraftRecipeUseCase]
 * (Wave 5s, ADR-0091).
 *
 * Each variant carries an i18n [messageKey] so command adapters can render a
 * localized message via `IMessageRenderer` without leaking domain detail.
 * Mirrors [com.astarworks.astera.core.domain.advancement.AdvancementError]'s
 * shape.
 */
public sealed class CraftError {
    public abstract val messageKey: MessageKey

    /** No recipe registered under the requested [RecipeId]. */
    public data class UnknownRecipe(public val recipeId: RecipeId) : CraftError() {
        override val messageKey: MessageKey = MessageKey("astera.recipe.error.unknown")
    }

    /** Player has not completed [criteria] yet — recipe still locked. */
    public data class Locked(public val criteria: AdvancementCriteria) : CraftError() {
        override val messageKey: MessageKey = MessageKey("astera.recipe.error.locked")
    }

    /** Player inventory lacks enough of [missing]. The map is `(item → still-needed)`. */
    public data class InsufficientIngredients(public val missing: Map<ItemId, Int>) : CraftError() {
        override val messageKey: MessageKey = MessageKey("astera.recipe.error.insufficient")

        init {
            require(missing.isNotEmpty()) { "CraftError.InsufficientIngredients.missing must not be empty" }
            missing.forEach { (id, n) ->
                require(n > 0) { "InsufficientIngredients.missing[$id] must be > 0, got $n" }
            }
        }
    }

    /**
     * Recipe declares a [CraftingStation] but the player tried to craft from a
     * different station (or no station at all).
     */
    public data class WrongStation(
        public val required: CraftingStation,
        public val actual: CraftingStation,
    ) : CraftError() {
        override val messageKey: MessageKey = MessageKey("astera.recipe.error.wrong-station")
    }

    /** Inventory snapshot lookup for the player failed — usually offline / never joined. */
    public data class NoInventory(public val reason: String) : CraftError() {
        override val messageKey: MessageKey = MessageKey("astera.recipe.error.no-inventory")
    }
}

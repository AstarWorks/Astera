package com.astarworks.astera.core.domain.recipe

/**
 * Stable identifier for a [CustomRecipe] (Wave 5s, ADR-0091).
 *
 * Lowercase kebab-case. Mirrors [com.astarworks.astera.core.domain.advancement.AdvancementId]'s
 * convention — content identifiers across Astera follow the same `[a-z][a-z0-9-]*`
 * grammar so YAML authors don't have to context-switch between domains.
 *
 * Recipes intentionally have their own id space (not reusing `ItemId`): a single
 * output [com.astarworks.astera.core.domain.item.ItemId] may have multiple
 * recipes (Shaped vs. Shapeless vs. Conversion variants), and the registry
 * looks recipes up by id, not output.
 */
@JvmInline
public value class RecipeId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "RecipeId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}

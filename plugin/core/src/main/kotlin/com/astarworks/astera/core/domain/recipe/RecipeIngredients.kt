package com.astarworks.astera.core.domain.recipe

import com.astarworks.astera.core.domain.item.ItemId

/**
 * The input side of a [CustomRecipe] (Wave 5s, ADR-0091).
 *
 * # Three crafting topologies
 *
 *  - [Shaped] mirrors Vanilla's "shaped recipe": a 2D pattern of single-character
 *    placeholders ('A', 'B', '#', etc.) each bound to an [ItemId] in [Shaped.key].
 *    Pattern rows must be equal-length and at most 3 chars (3x3 grid ceiling).
 *  - [Shapeless] is a multiset: a map of [ItemId] → required count. Order in
 *    the input doesn't matter, only that the player's inventory contains the
 *    multiset.
 *  - [Conversion] is the single-input / single-output variant — typical for
 *    smelting and enchant-table augmentation. [Conversion.ratio] is how many
 *    [Conversion.from] are consumed per output (e.g. ratio=2 means 2 input
 *    items yield 1 output; the use case multiplies by `outputCount`).
 *
 * # Why a sealed hierarchy instead of one flat record
 *
 * Each variant has different invariants (Shaped has positional structure,
 * Shapeless does not, Conversion has a single input). Exhaustive `when`
 * branching at the matcher (`CraftRecipeUseCase`) catches missing handling
 * at compile time when a new variant is added.
 *
 * # Phase-1 limits
 *
 * - Pattern dimensions: 1x1 to 3x3 (Vanilla parity).
 * - Shapeless: at most 9 distinct [ItemId] entries (Vanilla 3x3 ceiling).
 * - Conversion ratio: positive int; 1 = 1-for-1, higher = lossy compression.
 *
 * These are domain-level guard-rails (validated in `init`); the YAML schema
 * inherits them via the DTO → domain mapping.
 */
public sealed interface RecipeIngredients {

    /**
     * 2D-pattern recipe.
     *
     * @property pattern Up to 3 rows, each up to 3 chars; ' ' (space) denotes
     *  an empty slot. Rows must be equal length so the matcher can align rows.
     * @property key Single-character placeholders → required [ItemId]. Every
     *  non-space char in [pattern] must appear in [key].
     */
    public data class Shaped(
        public val pattern: List<String>,
        public val key: Map<Char, ItemId>,
    ) : RecipeIngredients {
        init {
            require(pattern.isNotEmpty()) { "Shaped.pattern must not be empty" }
            require(pattern.size <= MAX_DIM) { "Shaped.pattern at most $MAX_DIM rows, got ${pattern.size}" }
            val width = pattern.first().length
            require(width in 1..MAX_DIM) { "Shaped.pattern row width must be in 1..$MAX_DIM, got $width" }
            require(pattern.all { it.length == width }) {
                "Shaped.pattern rows must all be the same length ($width), got ${pattern.map { it.length }}"
            }
            val placeholders = pattern.flatMap { it.toList() }.filter { it != ' ' }.toSet()
            val unknown = placeholders - key.keys
            require(unknown.isEmpty()) {
                "Shaped.pattern placeholders not in key: $unknown (key has ${key.keys})"
            }
        }
    }

    /**
     * Multiset recipe — the player's inventory must contain the union of
     * these items (with sufficient counts). Order-independent.
     */
    public data class Shapeless(
        public val items: Map<ItemId, Int>,
    ) : RecipeIngredients {
        init {
            require(items.isNotEmpty()) { "Shapeless.items must not be empty" }
            require(items.size <= MAX_SHAPELESS) {
                "Shapeless.items at most $MAX_SHAPELESS distinct ItemIds, got ${items.size}"
            }
            items.forEach { (id, count) ->
                require(count > 0) { "Shapeless.items[$id] count must be > 0, got $count" }
            }
        }
    }

    /**
     * Single-input / single-output. Typical for smelting + enchant-table
     * augmentation.
     */
    public data class Conversion(
        public val from: ItemId,
        public val to: ItemId,
        public val ratio: Int = 1,
    ) : RecipeIngredients {
        init {
            require(ratio > 0) { "Conversion.ratio must be > 0, got $ratio" }
        }
    }

    public companion object {
        /** Largest legal pattern dimension (3x3 Vanilla parity). */
        public const val MAX_DIM: Int = 3
        /** Largest legal shapeless distinct-ingredient count (3x3 = 9 cells). */
        public const val MAX_SHAPELESS: Int = 9
    }
}

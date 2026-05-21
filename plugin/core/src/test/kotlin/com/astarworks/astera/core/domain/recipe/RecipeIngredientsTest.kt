package com.astarworks.astera.core.domain.recipe

import com.astarworks.astera.core.domain.item.ItemId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RecipeIngredients] invariant validation (Wave 5s, ADR-0091).
 *
 * Domain-level guard-rails (pattern dimensions, placeholder coverage, ratio
 * positivity) are validated in each variant's `init` block — they fail fast at
 * load time so malformed YAML never reaches the use case.
 */
class RecipeIngredientsTest {

    private val iron = ItemId("iron-ingot")
    private val stick = ItemId("stick")

    @Test
    fun `shaped accepts a valid 3x3 pattern`() {
        val recipe = RecipeIngredients.Shaped(
            pattern = listOf(" A ", " A ", " S "),
            key = mapOf('A' to iron, 'S' to stick),
        )
        assertThat(recipe.pattern).hasSize(3)
        assertThat(recipe.key).hasSize(2)
    }

    @Test
    fun `shaped rejects an empty pattern`() {
        assertThatThrownBy {
            RecipeIngredients.Shaped(pattern = emptyList(), key = emptyMap())
        }.hasMessageContaining("must not be empty")
    }

    @Test
    fun `shaped rejects rows wider than 3`() {
        assertThatThrownBy {
            RecipeIngredients.Shaped(
                pattern = listOf("AAAA"),
                key = mapOf('A' to iron),
            )
        }.hasMessageContaining("row width must be in 1..3")
    }

    @Test
    fun `shaped rejects uneven rows`() {
        assertThatThrownBy {
            RecipeIngredients.Shaped(
                pattern = listOf("AA", "AAA"),
                key = mapOf('A' to iron),
            )
        }.hasMessageContaining("rows must all be the same length")
    }

    @Test
    fun `shaped rejects placeholders not in key`() {
        assertThatThrownBy {
            RecipeIngredients.Shaped(
                pattern = listOf("AB"),
                key = mapOf('A' to iron),
            )
        }.hasMessageContaining("not in key")
    }

    @Test
    fun `shapeless accepts up to 9 entries`() {
        val items = (1..9).associate { ItemId("item-$it") to 1 }
        val recipe = RecipeIngredients.Shapeless(items = items)
        assertThat(recipe.items).hasSize(9)
    }

    @Test
    fun `shapeless rejects more than 9 entries`() {
        val items = (1..10).associate { ItemId("item-$it") to 1 }
        assertThatThrownBy { RecipeIngredients.Shapeless(items = items) }
            .hasMessageContaining("at most 9")
    }

    @Test
    fun `shapeless rejects zero counts`() {
        assertThatThrownBy {
            RecipeIngredients.Shapeless(items = mapOf(iron to 0))
        }.hasMessageContaining("count must be > 0")
    }

    @Test
    fun `conversion requires positive ratio`() {
        assertThatThrownBy {
            RecipeIngredients.Conversion(from = iron, to = stick, ratio = 0)
        }.hasMessageContaining("ratio must be > 0")
    }
}

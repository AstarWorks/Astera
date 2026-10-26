package com.astarworks.astera.core.application.config.recipe

import com.astarworks.astera.core.application.config.advancement.AdvancementCriteriaDto
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.recipe.CraftingStation
import com.astarworks.astera.core.domain.recipe.CustomRecipe
import com.astarworks.astera.core.domain.recipe.RecipeId
import com.astarworks.astera.core.domain.recipe.RecipeIngredients
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/recipes/` files (Wave 5s, ADR-0091).
 *
 * Mirrors [com.astarworks.astera.core.application.config.advancement.AdvancementYamlConfig]:
 * kept in `application/config/` so the domain stays free of
 * `kotlinx.serialization` annotations, and the wire schema can evolve
 * independently.
 *
 * # Wire format
 *
 * ## Shaped
 * ```yaml
 * id: example-sword
 * output_item: example-sword
 * output_count: 1
 * crafting_station: AsteraWorkbench
 * ingredients:
 *   type: shaped
 *   pattern:
 *     - " A "
 *     - " A "
 *     - " S "
 *   key:
 *     'A': iron-ingot
 *     'S': stick
 * ```
 *
 * ## Shapeless
 * ```yaml
 * id: dye-mix
 * output_item: purple-dye
 * output_count: 2
 * ingredients:
 *   type: shapeless
 *   items:
 *     red-dye: 1
 *     blue-dye: 1
 * ```
 *
 * ## Conversion (smelt / enchant)
 * ```yaml
 * id: smelt-iron
 * output_item: iron-ingot
 * output_count: 1
 * crafting_station: AsteraFurnace
 * ingredients:
 *   type: conversion
 *   from: iron-ore
 *   to: iron-ingot
 *   ratio: 1
 * unlock_criteria:
 *   type: kill_count
 *   target: 1
 * ```
 *
 * Each polymorphic variant uses kotlinx's `JsonClassDiscriminator("type")`
 * convention (kaml's `PolymorphismStyle.Property` reads the same field per
 * ADR-0016).
 *
 * `unlock_criteria` reuses [AdvancementCriteriaDto] from Wave 5p — recipes that
 * gate behind player progression do so by referencing the same criteria
 * vocabulary the advancement system uses, so authors don't relearn predicates.
 */
@Serializable
public data class RecipeYamlConfig(
    public val id: String,
    @SerialName("output_item") public val outputItem: String,
    @SerialName("output_count") public val outputCount: Int = 1,
    public val ingredients: RecipeIngredientsDto,
    @SerialName("crafting_station") public val craftingStation: CraftingStation? = null,
    @SerialName("unlock_criteria") public val unlockCriteria: AdvancementCriteriaDto? = null,
) {
    public fun toDomain(): CustomRecipe = CustomRecipe(
        id = RecipeId(id),
        outputItem = ItemId(outputItem),
        outputCount = outputCount,
        ingredients = ingredients.toDomain(),
        craftingStation = craftingStation,
        unlockCriteria = unlockCriteria?.toDomain(),
    )
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class RecipeIngredientsDto {

    public abstract fun toDomain(): RecipeIngredients

    @Serializable
    @SerialName("shaped")
    public data class Shaped(
        public val pattern: List<String>,
        public val key: Map<String, String>,
    ) : RecipeIngredientsDto() {
        override fun toDomain(): RecipeIngredients = RecipeIngredients.Shaped(
            pattern = pattern,
            key = key.entries.associate { (k, v) ->
                require(k.length == 1) {
                    "Shaped.key char must be a single character, got '$k'"
                }
                k.first() to ItemId(v)
            },
        )
    }

    @Serializable
    @SerialName("shapeless")
    public data class Shapeless(
        public val items: Map<String, Int>,
    ) : RecipeIngredientsDto() {
        override fun toDomain(): RecipeIngredients = RecipeIngredients.Shapeless(
            items = items.entries.associate { (id, count) -> ItemId(id) to count },
        )
    }

    @Serializable
    @SerialName("conversion")
    public data class Conversion(
        public val from: String,
        public val to: String,
        public val ratio: Int = 1,
    ) : RecipeIngredientsDto() {
        override fun toDomain(): RecipeIngredients = RecipeIngredients.Conversion(
            from = ItemId(from),
            to = ItemId(to),
            ratio = ratio,
        )
    }
}

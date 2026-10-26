package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.recipe.RecipeId
import java.time.Instant

/**
 * A custom recipe was successfully crafted by [playerId] (Wave 5s, ADR-0091).
 *
 * Published by [com.astarworks.astera.core.application.usecase.recipe.CraftRecipeUseCase]
 * after the player's inventory has been verified and the [output] item has been
 * counted as produced. Subscribers (Wave 5pve quest progression listener,
 * Wave 5u analytics, Wave 5p `ItemAcquired` advancement criterion bridge) use
 * this to drive cross-cutting reactions.
 *
 * # Recipe-driven `ItemAcquired`
 *
 * The advancement system's [com.astarworks.astera.core.domain.advancement.AdvancementCriteria.ItemAcquired]
 * leaf can listen on either [ItemAcquired] (give / pickup) or [RecipeCrafted]
 * (craft); the canonical bridge subscribes here and publishes [ItemAcquired]
 * to the same downstream listener — keeps the advancement use case unaware
 * of *how* an item was acquired.
 *
 * @property recipeId The recipe that was matched.
 * @property output The id of the item that was produced.
 * @property outputCount How many of [output] were produced (matches
 *  [com.astarworks.astera.core.domain.recipe.CraftResult.outputCount]).
 * @property atTick Server tick when the craft landed (for audit / replay).
 */
public data class RecipeCrafted(
    public val playerId: PlayerId,
    public val recipeId: RecipeId,
    public val output: ItemId,
    public val outputCount: Int,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent

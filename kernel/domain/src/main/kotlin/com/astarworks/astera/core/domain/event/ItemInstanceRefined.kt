package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.UUID

/**
 * Cross-plugin event (Wave 5refine, ADR-0100): a refinement layer was applied
 * to an [com.astarworks.astera.items.domain.refinement.ItemInstance].
 *
 * # When fired
 *
 * `ApplyRefinementUseCase` (items plugin) on every successful layer addition
 * (Reforge / Enchant / Gemstone / Star / Recomb).
 *
 * # Why centralised
 *
 * Several Wave 5+ tracks subscribe:
 *
 *   - Stats (Wave 5stats): grant the "Refining" skill XP per successful
 *     application.
 *   - Marketplace (Wave 5f / ADR-0035): invalidate cached effective stat
 *     summary on the listing, if any.
 *   - Analytics (Wave 12g): per-layer application rate, currency sink
 *     measurement.
 *
 * The [instanceUuid] carries the
 * [com.astarworks.astera.items.domain.refinement.ItemInstanceId.value] raw
 * UUID — Core does not depend on `:plugin:items` for typing per ADR-0022
 * (Core depends on no feature plugin).
 *
 * The [layerKind] is the snake-case discriminator of the Refinement variant
 * (`reforge` / `enchant` / `gemstone` / `star` / `recomb`) — string rather
 * than enum to keep this event additive-friendly per the Wave _items-1
 * pattern.
 */
public data class ItemInstanceRefined(
    public val instanceUuid: UUID,
    public val itemId: ItemId,
    public val owner: PlayerId,
    public val layerKind: String,
    public val totalLayers: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(owner.uuid)

    init {
        require(layerKind.isNotBlank()) { "ItemInstanceRefined.layerKind must not be blank." }
        require(totalLayers >= 1) {
            "ItemInstanceRefined.totalLayers must be >= 1 after the application, got $totalLayers"
        }
    }
}

package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.GatheringSkillTrigger
import com.astarworks.astera.core.domain.survival.GatheringSpotId
import com.astarworks.astera.core.domain.world.WorldId
import java.time.Instant

/**
 * Cross-plugin event (Wave 5pve-survival / ADR-0107): a player has just
 * harvested a [com.astarworks.astera.core.domain.survival.GatheringSpot]
 * and the loot has been rolled.
 *
 * # When fired
 *
 * `HarvestGatheringSpotUseCase` (core plugin) after:
 *   1. Per-spot cooldown gate passes.
 *   2. Per-player [com.astarworks.astera.core.domain.survival.GatheringProgress] cooldown passes.
 *   3. Yield is rolled via `IGatheringYieldRoller`.
 *   4. `IGatheringProgressRepository.recordHarvest` succeeds.
 *
 * # Subscribers
 *
 * - Wave _items-1 inventory adapter (Paper-side, Wave 6): grant the
 *   [rolledYields] entries to the player's inventory.
 * - Wave 5pve XP distributor: optional gathering-XP grant (deferred to
 *   Phase 4 when gathering XP curve is finalised).
 * - Wave 12g analytics: per-day per-spot harvest histogram.
 *
 * # Why ItemId + count instead of full ItemInstance
 *
 * Foundation wave does not yet generate ItemInstance refinements per gather
 * (refinement is Wave 5refine and gates on combat-quality items). Reducing
 * the payload to `(ItemId, count)` tuples keeps the event light and lets
 * the inventory adapter pick the materialisation strategy (stack-merge,
 * fresh instance, …).
 */
public data class GatheringSpotHarvested(
    val spotId: GatheringSpotId,
    val playerId: PlayerId,
    val trigger: GatheringSkillTrigger,
    val world: WorldId,
    val rolledYields: Map<ItemId, Int>,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)

    init {
        require(rolledYields.isNotEmpty()) {
            "GatheringSpotHarvested.rolledYields must not be empty (cooldown-gated successful harvest produces ≥1 entry)"
        }
        rolledYields.forEach { (itemId, count) ->
            require(count > 0) {
                "GatheringSpotHarvested.rolledYields[$itemId] must be > 0, was $count"
            }
        }
    }
}

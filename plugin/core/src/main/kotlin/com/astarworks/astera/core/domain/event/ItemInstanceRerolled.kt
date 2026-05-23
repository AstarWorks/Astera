package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant
import java.util.UUID

/**
 * Cross-plugin event (Wave 5refine, ADR-0100 §"RerollSubStatsUseCase"): a
 * sub-stat reroll was attempted on an `ItemInstance`.
 *
 * Fired regardless of [keptResult] — both the *keep new roll* and *discard
 * new roll* branches publish so analytics + leaderboards see the full
 * attempt-rate signal (ADR-0100 §Mitigations references Wave 12g analytics).
 *
 * @property keptResult `true` if the player committed the new roll set;
 *   `false` if they chose to keep the prior rolls. The cost is paid either
 *   way — this records *which branch* the player took, not whether the
 *   transaction succeeded (use case returns `Result.Failure` on cost failure
 *   and never publishes this event in that path).
 *
 * @property rerollIndex zero-based count of the reroll *before* this attempt,
 *   so the next event's value increments by exactly 1. Lets a subscriber
 *   reconstruct the cost paid via
 *   [com.astarworks.astera.items.domain.refinement.RefinementMath.rerollCost].
 */
public data class ItemInstanceRerolled(
    public val instanceUuid: UUID,
    public val itemId: ItemId,
    public val owner: PlayerId,
    public val keptResult: Boolean,
    public val rerollIndex: Int,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(rerollIndex >= 0) {
            "ItemInstanceRerolled.rerollIndex must be >= 0, got $rerollIndex"
        }
    }
}

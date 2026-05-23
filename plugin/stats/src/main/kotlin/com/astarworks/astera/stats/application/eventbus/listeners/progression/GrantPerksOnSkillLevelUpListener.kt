package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.ISkillPerkRegistry
import com.astarworks.astera.core.domain.event.SkillLevelUp
import com.astarworks.astera.core.domain.event.SkillPerkUnlocked
import com.astarworks.astera.core.domain.progression.SkillProgressionPerk

/**
 * On [SkillLevelUp], resolves the perks newly unlocked between
 * `oldLevel` and `newLevel`, publishes a [SkillPerkUnlocked] per perk on the
 * in-process bus, and broadcasts each via [IBroadcaster] for external
 * observers (Wave 5multi-skill, ADR-0101).
 *
 * # Phase 1 scope
 *
 * The actual perk application — applying a
 * [com.astarworks.astera.core.domain.progression.SkillPerkEffect.StatMultiplier]
 * to the player's attribute container, materialising a feature flag, etc. —
 * is deferred to a follow-up wave (Wave 5p Advancement already covers the
 * RewardBundle path for items / currency / titles; multi-skill perks are
 * stat modifiers, a category that needs the Wave 5e
 * `PlayerAttributeContainer` modifier-stack work to land first).
 *
 * For Phase 1 this listener:
 *  1. Looks up the perks crossed in `(oldLevel, newLevel]` via [ISkillPerkRegistry].
 *  2. Publishes each as [SkillPerkUnlocked] on the in-process bus (future
 *     applier listeners subscribe here).
 *  3. Re-broadcasts each via [IBroadcaster] so audit / Discord notifier sees
 *     them.
 *
 * Future revision (post-Wave 5e attribute-modifier work) will:
 *  - For `StatMultiplier` / `StatAdditive`: push a modifier onto the
 *    player's attribute container with `sourceId =
 *    "skill:${kind.token}:${perk.id}"`.
 *  - For `UnlockFeature`: deliver a flag via the feature-gating service.
 */
public class GrantPerksOnSkillLevelUpListener(
    private val perkRegistry: ISkillPerkRegistry,
    private val eventBus: IDomainEventBus,
    private val broadcaster: IBroadcaster,
) {
    public fun handle(event: SkillLevelUp) {
        val unlocked: List<SkillProgressionPerk> = perkRegistry.perksUnlockedBetween(
            kind = event.kind,
            oldLevel = event.oldLevel,
            newLevel = event.newLevel,
        )
        if (unlocked.isEmpty()) return
        unlocked.forEach { perk ->
            val notification = SkillPerkUnlocked(
                playerId = event.playerId,
                kind = event.kind,
                perk = perk,
                occurredAt = event.occurredAt,
            )
            eventBus.publish(notification)
            broadcaster.publish(notification)
        }
    }
}

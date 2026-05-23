package com.astarworks.astera.stats.application.eventbus.listeners.progression

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.domain.event.SkillLevelUp

/**
 * Re-publishes [SkillLevelUp] via the [IBroadcaster] outbound so external
 * observers (Discord notifier, Redis pub-sub, audit log) see the milestone.
 *
 * Mirrors [com.astarworks.astera.stats.application.eventbus.listeners.BroadcastOnLeveledUpListener]
 * for the multi-skill axis (Wave 5multi-skill, ADR-0101).
 *
 * In-process HUD pings stay on the [com.astarworks.astera.core.application.port.outbound.IDomainEventBus]
 * channel — this listener is the seam to the *outbound* broadcaster only.
 */
public class BroadcastOnSkillLevelUpListener(
    private val broadcaster: IBroadcaster,
) {
    public fun handle(event: SkillLevelUp) {
        broadcaster.publish(event)
    }
}

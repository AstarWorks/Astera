package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.stats.domain.event.LeveledUp

/**
 * Subscribes to in-process [LeveledUp] events and re-publishes them via
 * [IBroadcaster] for external fan-out (Discord notifier, Redis pub/sub,
 * persistence audit log). Phase 1 [IBroadcaster] only logs; Wave 5 will
 * register Redis / Discord adapters.
 *
 * silly-knuth §5 — twin-stage pattern: `IDomainEventBus` is the in-process
 * single source of truth, this listener is the seam that escalates a
 * specific subset (level-ups) to external observers. Other [LeveledUp]
 * subscribers (chat title updates, etc.) keep using the same in-process bus.
 */
public class BroadcastOnLeveledUpListener(
    private val broadcaster: IBroadcaster,
) {
    public fun handle(event: LeveledUp) {
        broadcaster.publish(event)
    }
}

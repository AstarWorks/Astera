package com.astarworks.astera.core.application.service.admin

import com.astarworks.astera.core.application.port.outbound.IBroadcaster
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementError
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementSpec
import com.astarworks.astera.core.application.port.outbound.admin.AnnouncementTrigger
import com.astarworks.astera.core.application.port.outbound.admin.IScheduledAnnouncementSystem
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.admin.AnnouncementBroadcastKind
import com.astarworks.astera.core.domain.admin.AnnouncementId
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.ScheduledAnnouncementFired
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration

/**
 * Default [IScheduledAnnouncementSystem] implementation.
 *
 *   - [AnnouncementTrigger.Once]    → [IScheduler.runLater] with the delay
 *     between `Instant.now()` and the trigger instant (negative delays fire
 *     immediately).
 *   - [AnnouncementTrigger.Interval] → [IScheduler.runRepeating] with
 *     `initialDelay = period`.
 *   - [AnnouncementTrigger.OnEvent]  → [IDomainEventBus.subscribe] filtered
 *     by `event::class.simpleName == eventClassName`.
 *
 * On every fire the announcement publishes a [ScheduledAnnouncementFired]
 * domain event (so subscribers — e.g., audit log — can observe deliveries)
 * and routes the message through [IBroadcaster]. Once-shot announcements are
 * pruned after firing.
 */
public class InMemoryScheduledAnnouncementSystem(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val broadcaster: IBroadcaster,
) : IScheduledAnnouncementSystem {

    private data class Active(
        val spec: AnnouncementSpec,
        val cancelHook: () -> Unit,
    )

    private val active: MutableMap<AnnouncementId, Active> = ConcurrentHashMap()

    override fun schedule(spec: AnnouncementSpec): Result<AnnouncementId, AnnouncementError> {
        if (active.containsKey(spec.id)) {
            return Result.failure(AnnouncementError.DuplicateId(spec.id))
        }

        val cancelHook: () -> Unit = when (val trigger = spec.trigger) {
            is AnnouncementTrigger.Once -> {
                val nowMs = Instant.now().toEpochMilli()
                val deltaMs = (trigger.at.toEpochMilli() - nowMs).coerceAtLeast(0L)
                val handle = scheduler.runLater(Duration.ofMillis(deltaMs).toKotlinDuration()) {
                    fire(spec)
                    active.remove(spec.id)
                }
                ({ handle.cancel() })
            }
            is AnnouncementTrigger.Interval -> {
                val period = trigger.period.toKotlinDuration()
                val handle = scheduler.runRepeating(period, period) { fire(spec) }
                ({ handle.cancel() })
            }
            is AnnouncementTrigger.OnEvent -> {
                val sub = eventBus.subscribe(DomainEvent::class) { ev ->
                    if (ev::class.simpleName == trigger.eventClassName) {
                        fire(spec)
                    }
                }
                ({ sub.cancel() })
            }
        }
        active[spec.id] = Active(spec, cancelHook)
        return Result.success(spec.id)
    }

    override fun cancel(id: AnnouncementId): Result<Unit, AnnouncementError> {
        val entry = active.remove(id)
            ?: return Result.failure(AnnouncementError.UnknownId(id))
        entry.cancelHook()
        return Result.success(Unit)
    }

    override fun list(): Collection<AnnouncementSpec> = active.values.map { it.spec }

    private fun fire(spec: AnnouncementSpec) {
        // Broadcaster integration is intentionally lightweight here — Wave 5t
        // commits the announcement port; richer broadcast surfaces
        // (action-bar / title) land in Wave 6 alongside HUD ports.
        @Suppress("UNUSED_VARIABLE")
        val kind: AnnouncementBroadcastKind = spec.broadcastKind
        broadcaster.publish(
            ScheduledAnnouncementFired(
                id = spec.id,
                messageKey = spec.messageKey,
                broadcastKind = spec.broadcastKind,
            ),
        )
    }
}

package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.world.IRegionEventFilter
import com.astarworks.astera.core.domain.event.ChunkLoaded
import com.astarworks.astera.core.domain.event.ChunkUnloaded
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.event.PlayerInteractedBlock
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.event.PlayerTeleported
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.reflect.KClass

/**
 * Phase 1 in-memory implementation of [IRegionEventFilter]
 * (Wave 5world-events, ADR-0111).
 *
 * Wraps an [IDomainEventBus] subscription with a **position-extraction +
 * region-membership** decorator. Each registered subscription:
 *
 *  1. Subscribes through the underlying event bus on the same [type].
 *  2. On each incoming event, runs [positionExtractor] to derive `(world,
 *     position)` — events with no positional payload (e.g.
 *     [com.astarworks.astera.core.domain.event.WeatherChanged]) are dropped
 *     silently.
 *  3. Runs [regionMembership] to test whether `(world, position)` falls
 *     inside [regionId]. Matching events flow to the handler; non-matches
 *     are dropped.
 *
 * The default ctor wires the canonical extractor (covers [PlayerMoved],
 * [PlayerTeleported], [PlayerInteractedBlock], [ChunkLoaded],
 * [ChunkUnloaded]) and a "not yet wired" membership predicate that always
 * returns `false`. The Wave 5n region-permission wiring overrides the
 * predicate with the actual spatial-index call.
 *
 * @property positionExtractor Maps a domain event to its `(world, position)`
 *   payload, or `null` if the event has no spatial meaning.
 * @property regionMembership `(regionId, world, position) -> Boolean`. The
 *   Wave 5n binding overrides this to consult `IStageRegionIndex`.
 */
public class InMemoryRegionEventFilter(
    private val eventBus: IDomainEventBus,
    private val positionExtractor: (DomainEvent) -> Pair<WorldId, Vec3>? = ::defaultExtractor,
    private val regionMembership: (RegionId, WorldId, Vec3) -> Boolean = { _, _, _ -> false },
) : IRegionEventFilter {

    override fun <E : DomainEvent> subscribe(
        regionId: RegionId,
        type: KClass<E>,
        delivery: com.astarworks.astera.core.application.port.outbound.EventDelivery,
        handler: (E) -> Unit,
    ): IRegionEventFilter.Subscription {
        val inner = eventBus.subscribe(type, delivery) { event ->
            val pos = positionExtractor(event) ?: return@subscribe
            if (regionMembership(regionId, pos.first, pos.second)) {
                handler(event)
            }
        }
        return object : IRegionEventFilter.Subscription {
            override fun cancel(): Unit = inner.cancel()
            override val isActive: Boolean get() = inner.isActive
        }
    }

    public companion object {
        /**
         * Canonical position extractor — covers every Wave 5world-events
         * spatial event. Returns `null` for events that have no positional
         * meaning so they are filtered out (the bus still routes them to
         * any non-region subscriber).
         */
        public fun defaultExtractor(event: DomainEvent): Pair<WorldId, Vec3>? = when (event) {
            is PlayerMoved -> event.world to event.to
            is PlayerTeleported -> event.worldTo to event.to
            is PlayerInteractedBlock -> event.world to event.position
            is ChunkLoaded -> event.world to chunkCenter(event.chunkX, event.chunkZ)
            is ChunkUnloaded -> event.world to chunkCenter(event.chunkX, event.chunkZ)
            else -> null
        }

        private fun chunkCenter(chunkX: Int, chunkZ: Int): Vec3 = Vec3(
            x = chunkX * 16.0 + 8.0,
            y = 64.0,
            z = chunkZ * 16.0 + 8.0,
        )
    }
}

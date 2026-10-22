package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.HudCleared
import com.astarworks.astera.core.domain.event.HudShown
import com.astarworks.astera.core.domain.event.HudUpdated
import com.astarworks.astera.core.domain.event.ResourcePackPushRequested
import com.astarworks.astera.core.domain.hud.HudChannelPolicy
import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.region
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IHudGateway] for use case tests (Wave 5j, ADR-0097).
 *
 * Same observable contract as
 * [com.astarworks.astera.core.application.service.InMemoryHudGateway] but
 * keeps a full [operations] history for ordered assertion, plus simulators
 * for resource-pack accept / decline so tests can drive Wave 6b's
 * `PlayerResourcePackStatusEvent` flow without a Bukkit harness.
 *
 * Typical test flow:
 * ```
 * val bus = FakeDomainEventBus()
 * val gateway = FakeHudGateway(eventBus = bus)
 * useCase.run(gateway)
 * assertThat(gateway.operations).hasSize(1)
 * assertThat(bus.publishedOfType<HudShown>()).hasSize(1)
 * ```
 *
 * [eventBus] and [scheduler] are nullable so tests that don't care can
 * construct `FakeHudGateway()` with no arguments. Pass a [FakeScheduler]
 * to exercise ActionBar TTL auto-clear deterministically.
 */
public class FakeHudGateway(
    private val eventBus: IDomainEventBus? = null,
    private val scheduler: IScheduler? = null,
) : IHudGateway {

    /**
     * Operation history. Tests assert on this list to verify the use case
     * called the gateway with the right arguments in the right order.
     */
    public sealed interface HudOp {
        public data class Shown(val playerId: PlayerId, val handle: HudHandle, val layout: HudLayout) : HudOp
        public data class Updated(val handle: HudHandle, val layout: HudLayout) : HudOp
        public data class Cleared(val playerId: PlayerId, val region: HudRegion, val handles: List<HudHandle>) : HudOp
        public data class ResourcePackPushed(val playerId: PlayerId, val pack: ResourcePackSpec) : HudOp
        public data class ResourcePackAccepted(val playerId: PlayerId, val packHash: String) : HudOp
        public data class ResourcePackDeclined(val playerId: PlayerId, val packHash: String) : HudOp
    }

    private data class ActiveHud(
        val playerId: PlayerId,
        val layout: HudLayout,
        /** Tick at which an action bar stops being visible (see [HudChannelPolicy]). */
        val expiresAt: Long = Long.MAX_VALUE,
        /** Bumped on every show / update so a stale TTL callback cannot clear newer content. */
        val gen: Int = 0,
    )

    private val active: ConcurrentHashMap<HudHandle, ActiveHud> = ConcurrentHashMap()
    private val ops: MutableList<HudOp> = mutableListOf()

    /** Ordered call sequence — for tests asserting operation order. */
    public val operations: List<HudOp> get() = ops.toList()

    override fun showFor(
        playerId: PlayerId,
        layout: HudLayout,
    ): Result<HudHandle, HudError> {
        val now = nowTick()
        // One live handle per (player, region): replace in place instead of stacking.
        val occupant = active.entries.firstOrNull { (_, hud) ->
            hud.playerId == playerId && hud.layout.region() == layout.region()
        }
        if (occupant != null) {
            if (HudChannelPolicy.suppresses(occupant.value.layout, occupant.value.expiresAt, layout, now)) {
                return Result.success(occupant.key)
            }
            return when (val updated = updateFor(occupant.key, layout)) {
                is Result.Success -> Result.success(occupant.key)
                is Result.Failure -> Result.failure(updated.error)
            }
        }
        val handle = HudHandle(UUID.randomUUID().toString())
        active[handle] = ActiveHud(playerId, layout, HudChannelPolicy.expiresAt(layout, now), gen = 0)
        ops += HudOp.Shown(playerId, handle, layout)
        eventBus?.publish(
            HudShown(
                playerId = playerId,
                handle = handle,
                layout = layout,
            ),
        )
        scheduleActionBarTtl(playerId, handle, layout, gen = 0)
        return Result.success(handle)
    }

    override fun updateFor(
        handle: HudHandle,
        layout: HudLayout,
    ): Result<Unit, HudError> {
        val current = active[handle]
            ?: return Result.failure(HudError.UnknownHandle(handle))
        val gen = current.gen + 1
        active[handle] = current.copy(
            layout = layout,
            expiresAt = HudChannelPolicy.expiresAt(layout, nowTick()),
            gen = gen,
        )
        ops += HudOp.Updated(handle, layout)
        eventBus?.publish(
            HudUpdated(
                playerId = current.playerId,
                handle = handle,
                layout = layout,
            ),
        )
        scheduleActionBarTtl(current.playerId, handle, layout, gen)
        return Result.success(Unit)
    }

    override fun clearFor(
        playerId: PlayerId,
        region: HudRegion,
    ): Result<Unit, HudError> {
        val matching = active.entries
            .filter { (_, hud) ->
                hud.playerId == playerId && hud.layout.region() == region
            }
            .map { it.key }
        for (handle in matching) {
            active.remove(handle)
        }
        ops += HudOp.Cleared(playerId, region, matching)
        eventBus?.publish(
            HudCleared(
                playerId = playerId,
                region = region,
                clearedHandles = matching,
            ),
        )
        return Result.success(Unit)
    }

    override fun pushResourcePack(
        playerId: PlayerId,
        pack: ResourcePackSpec,
    ): Result<Unit, HudError> {
        ops += HudOp.ResourcePackPushed(playerId, pack)
        eventBus?.publish(
            ResourcePackPushRequested(
                playerId = playerId,
                pack = pack,
            ),
        )
        return Result.success(Unit)
    }

    override fun activeFor(playerId: PlayerId): Collection<HudHandle> =
        active.entries
            .filter { it.value.playerId == playerId }
            .map { it.key }

    /** Current layout for a handle, or null if cleared. For state assertions. */
    public fun currentLayout(handle: HudHandle): HudLayout? = active[handle]?.layout

    /**
     * Simulate the player's client accepting a previously-pushed resource
     * pack (Wave 6b adapter will observe this via
     * `PlayerResourcePackStatusEvent`). Records the event in [operations];
     * does not currently publish a domain event (the
     * `ResourcePackAccepted` domain event itself lands in Wave 6b alongside
     * the Paper adapter, intentionally not added here).
     */
    public fun simulateClientAcceptedPack(playerId: PlayerId, packHash: String) {
        ops += HudOp.ResourcePackAccepted(playerId, packHash)
    }

    /** Counterpart to [simulateClientAcceptedPack] for client-side decline. */
    public fun simulateClientDeclinedPack(playerId: PlayerId, packHash: String) {
        ops += HudOp.ResourcePackDeclined(playerId, packHash)
    }

    /**
     * Test cleanup hook — drops every recorded op and clears the active map.
     * Useful in `@AfterEach` when reusing one instance across tests.
     */
    public fun clear() {
        active.clear()
        ops.clear()
    }

    private fun nowTick(): Long = scheduler?.currentTick() ?: 0L

    private fun scheduleActionBarTtl(
        playerId: PlayerId,
        handle: HudHandle,
        layout: HudLayout,
        gen: Int,
    ) {
        val sched = scheduler ?: return
        if (layout !is HudLayout.ActionBar) return
        val ttl = layout.ttlTicks
        if (ttl <= 0) return
        sched.runLater(ticks(ttl.toLong())) {
            // Only the latest show / update of the handle may expire it.
            val current = active[handle] ?: return@runLater
            if (current.playerId != playerId || current.gen != gen) return@runLater
            clearFor(playerId, HudRegion.ACTION_BAR)
        }
    }
}

package com.astarworks.astera.core.application.service

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
 * Default in-memory [IHudGateway] (Wave 5j, ADR-0097).
 *
 * Wired by `AsteraCorePlugin` at priority 0 — Wave 6b's `PaperHudGateway`
 * will register at higher priority and shadow this impl on real servers.
 * This gateway is a **no-op for the UI side** (Core has no Paper API) but
 * publishes the full [HudShown] / [HudUpdated] / [HudCleared] /
 * [ResourcePackPushRequested] event chain so cross-plugin audit /
 * analytics subscribers stay wired correctly in Phase 1.
 *
 * **TTL auto-clear:** if [scheduler] is provided and a [HudLayout.ActionBar]
 * is shown with `ttlTicks > 0`, the gateway schedules a `clearFor(...,
 * ACTION_BAR)` after that many ticks. The Paper Action Bar UI does this
 * client-side anyway; the gateway mirrors the behaviour in its tracking
 * map so `activeFor` doesn't return stale handles. Other layouts have no
 * automatic expiry — callers manage their lifecycle explicitly.
 *
 * **Not thread-safe** beyond what [ConcurrentHashMap] provides. Astera's
 * scheduler contract means HUD mutation runs on the server main thread;
 * the concurrent map is precautionary for Wave 6b packet listeners and
 * for the scheduler-driven TTL callback firing on a different thread.
 */
public class InMemoryHudGateway(
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler? = null,
) : IHudGateway {

    private data class ActiveHud(
        val playerId: PlayerId,
        val layout: HudLayout,
        /** Tick at which an action bar stops being visible (see [HudChannelPolicy]). */
        val expiresAt: Long = Long.MAX_VALUE,
        /** Bumped on every show / update so a stale TTL callback cannot clear newer content. */
        val gen: Int = 0,
    )

    private val active: ConcurrentHashMap<HudHandle, ActiveHud> = ConcurrentHashMap()

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
        eventBus.publish(
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
        eventBus.publish(
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
        eventBus.publish(
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
        // No real push: Wave 6b PaperHudGateway calls Player.setResourcePack().
        // Here we only publish so subscribers (audit, analytics, future
        // ResourcePackStatus correlator) can be wired ahead of the adapter.
        eventBus.publish(
            ResourcePackPushRequested(
                playerId = playerId,
                pack = pack,
            ),
        )
        return Result.success(Unit)
    }

    override fun layoutsFor(playerId: PlayerId): List<HudLayout> =
        active.values.filter { it.playerId == playerId }.map { it.layout }

    override fun activeFor(playerId: PlayerId): Collection<HudHandle> =
        active.entries
            .filter { it.value.playerId == playerId }
            .map { it.key }

    /**
     * Maps a layout to the screen region the port's [clearFor] uses.
     *
     * Kept private and exhaustive (sealed `HudLayout` hierarchy) so a new
     * layout variant forces a compile error here — preventing the silent
     * "clear didn't find my handle" footgun a default-fallthrough would
     * create.
     */
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

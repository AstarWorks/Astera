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
import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
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
    )

    private val active: ConcurrentHashMap<HudHandle, ActiveHud> = ConcurrentHashMap()

    override fun showFor(
        playerId: PlayerId,
        layout: HudLayout,
    ): Result<HudHandle, HudError> {
        val handle = HudHandle(UUID.randomUUID().toString())
        active[handle] = ActiveHud(playerId, layout)
        eventBus.publish(
            HudShown(
                playerId = playerId,
                handle = handle,
                layout = layout,
            ),
        )
        scheduleActionBarTtl(playerId, handle, layout)
        return Result.success(handle)
    }

    override fun updateFor(
        handle: HudHandle,
        layout: HudLayout,
    ): Result<Unit, HudError> {
        val current = active[handle]
            ?: return Result.failure(HudError.UnknownHandle(handle))
        active[handle] = current.copy(layout = layout)
        eventBus.publish(
            HudUpdated(
                playerId = current.playerId,
                handle = handle,
                layout = layout,
            ),
        )
        return Result.success(Unit)
    }

    override fun clearFor(
        playerId: PlayerId,
        region: HudRegion,
    ): Result<Unit, HudError> {
        val matching = active.entries
            .filter { (_, hud) ->
                hud.playerId == playerId && regionOf(hud.layout) == region
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
    private fun regionOf(layout: HudLayout): HudRegion = when (layout) {
        is HudLayout.ActionBar -> HudRegion.ACTION_BAR
        is HudLayout.BossBarSlot -> when (layout.slot) {
            1 -> HudRegion.BOSS_BAR_SLOT_1
            2 -> HudRegion.BOSS_BAR_SLOT_2
            3 -> HudRegion.BOSS_BAR_SLOT_3
            4 -> HudRegion.BOSS_BAR_SLOT_4
            else -> error("BossBarSlot.slot out of range: ${layout.slot}") // init-block rejects this
        }
        is HudLayout.Sidebar -> HudRegion.SIDEBAR
        // TabList header+footer share the same logical layout; we map to the
        // header region for clear purposes. clearFor(TAB_LIST_HEADER) and
        // clearFor(TAB_LIST_FOOTER) both clear the TabList — adapters that
        // treat them as independent surfaces will split this in Wave 6b.
        is HudLayout.TabList -> HudRegion.TAB_LIST_HEADER
        is HudLayout.Subtitle -> HudRegion.SUBTITLE
    }

    private fun scheduleActionBarTtl(
        playerId: PlayerId,
        handle: HudHandle,
        layout: HudLayout,
    ) {
        val sched = scheduler ?: return
        if (layout !is HudLayout.ActionBar) return
        val ttl = layout.ttlTicks
        if (ttl <= 0) return
        sched.runLater(ticks(ttl.toLong())) {
            // Only auto-clear if the handle is still ours and unchanged —
            // an explicit updateFor / clearFor before TTL fires takes
            // precedence. The map check protects us; the layout check
            // prevents clearing a handle reused for a different region.
            val current = active[handle] ?: return@runLater
            if (current.playerId != playerId) return@runLater
            if (current.layout !is HudLayout.ActionBar) return@runLater
            clearFor(playerId, HudRegion.ACTION_BAR)
        }
    }
}

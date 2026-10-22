package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.HudCleared
import com.astarworks.astera.core.domain.event.HudShown
import com.astarworks.astera.core.domain.event.HudUpdated
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.region
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Recording view of the HUD (WP14): follows the core HUD gateway's
 * [HudShown] / [HudUpdated] / [HudCleared] events and keeps, per player and
 * region, the layout that is on screen right now plus a chronological log of
 * every change. Scenarios assert on it ("the sidebar shows 2/3 pillars for
 * red"); the trace gets a `hud` entry per change for the viewer.
 */
public class SimHudRecorder(
    private val recorder: TraceRecorder,
    private val now: () -> Long,
) {
    public enum class Op { SHOW, UPDATE, CLEAR }

    public data class Entry(
        val tick: Long,
        val player: PlayerId,
        val region: HudRegion,
        val op: Op,
        val layout: HudLayout?,
    )

    private val current: MutableMap<Pair<PlayerId, HudRegion>, HudLayout> = mutableMapOf()
    private val log: MutableList<Entry> = mutableListOf()

    /** Every change so far, in order. */
    public val entries: List<Entry> get() = log.toList()

    public fun changes(player: PlayerId, region: HudRegion): List<Entry> =
        log.filter { it.player == player && it.region == region }

    public fun layout(player: PlayerId, region: HudRegion): HudLayout? = current[player to region]

    public fun sidebar(player: PlayerId): HudLayout.Sidebar? = layout(player, HudRegion.SIDEBAR) as? HudLayout.Sidebar

    public fun actionBar(player: PlayerId): HudLayout.ActionBar? = layout(player, HudRegion.ACTION_BAR) as? HudLayout.ActionBar

    public fun bossBar(player: PlayerId, slot: Int = 1): HudLayout.BossBarSlot? =
        layout(player, regionOfSlot(slot)) as? HudLayout.BossBarSlot

    /** Sidebar lines as `key{placeholders}` strings, top to bottom (empty when no sidebar is shown). */
    public fun sidebarText(player: PlayerId): List<String> =
        sidebar(player)?.lines?.map { describe(it) }.orEmpty()

    internal fun attach(bus: IDomainEventBus) {
        bus.subscribe(HudShown::class) { record(it.playerId, it.layout.region(), Op.SHOW, it.layout) }
        bus.subscribe(HudUpdated::class) { record(it.playerId, it.layout.region(), Op.UPDATE, it.layout) }
        bus.subscribe(HudCleared::class) { record(it.playerId, it.region, Op.CLEAR, null) }
    }

    private fun record(player: PlayerId, region: HudRegion, op: Op, layout: HudLayout?) {
        if (layout == null) current.remove(player to region) else current[player to region] = layout
        log += Entry(now(), player, region, op, layout)
        recorder.recordHud(now(), player, region.name, op.name.lowercase(), layout?.let(::summary).orEmpty())
    }

    private fun summary(layout: HudLayout): String = when (layout) {
        is HudLayout.Sidebar -> "${layout.titleKey.value} | " + layout.lines.joinToString(" / ") { describe(it) }
        is HudLayout.ActionBar -> describe(layout.component)
        is HudLayout.BossBarSlot -> describe(layout.component) + " @%.2f".format(layout.progress)
        is HudLayout.TabList -> "tab-list"
        is HudLayout.Subtitle -> describe(layout.component)
    }

    private fun regionOfSlot(slot: Int): HudRegion = when (slot) {
        1 -> HudRegion.BOSS_BAR_SLOT_1
        2 -> HudRegion.BOSS_BAR_SLOT_2
        3 -> HudRegion.BOSS_BAR_SLOT_3
        else -> HudRegion.BOSS_BAR_SLOT_4
    }

    public companion object {
        /** `key{a=1,b=2}` for text, concatenation for composites. */
        public fun describe(component: HudComponent): String = when (component) {
            is HudComponent.Text ->
                if (component.placeholders.isEmpty()) component.messageKey.value
                else component.messageKey.value + component.placeholders.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
            is HudComponent.Composite -> component.parts.joinToString(" ") { describe(it) }
            is HudComponent.Glyph -> "glyph:${component.textureRef.value}"
            is HudComponent.ProgressBar -> "bar:${component.percent}"
            is HudComponent.Spacer -> "spacer"
        }
    }
}

package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.EventDelivery

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import kotlinx.serialization.json.*
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.HudCleared
import com.astarworks.astera.core.domain.event.HudShown
import com.astarworks.astera.core.domain.event.HudUpdated
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.region
import com.astarworks.astera.core.domain.hud.status.StatusEffectHudGlyphs
import com.astarworks.astera.core.domain.hud.status.StatusHudGlyphs
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
    private val messages: IMessageRenderer,
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

    public fun statusLine(player: PlayerId): HudLayout.StatusLine? =
        layout(player, HudRegion.STATUS_LINE) as? HudLayout.StatusLine

    /** The status line as text: `hp-bar:cur/max` (glyph form) or `key{current=..,max=..}` (text form). */
    public fun statusLineText(player: PlayerId): String? = statusLine(player)?.let { describe(it.component) }

    /**
     * The ammo shown on the status line as `cur/max`, or `null` when none is (F2-2, ADR-0194): read from
     * the glyph form (after the bullet icon) or from the `ammo` / `ammoMax` placeholders of the text form.
     */
    public fun statusAmmo(player: PlayerId): String? {
        val line = statusLine(player)?.component ?: return null
        // The text form carries the cooldown parts after the HP text (ADR-0197): the HP text is first.
        val component = if (line is HudComponent.Composite) line.parts.firstOrNull() ?: return null else line
        return when (component) {
            is HudComponent.FontText -> ammoNumbers(component)
            is HudComponent.Text -> component.placeholders["ammo"]?.let { "$it/${component.placeholders["ammoMax"]}" }
            else -> null
        }
    }

    /** Decoded effect IDs, seconds and stacks from the packed glyph row. */
    public fun statusEffects(player: PlayerId): List<StatusEffectReadout> =
        (statusLine(player)?.component as? HudComponent.FontText)?.let(::effectReadouts).orEmpty()

    public data class StatusEffectReadout(val id: String, val seconds: Int, val stacks: Int)

    /** Whether any layout ever recorded for [player] or anyone else is a boss bar: there is no such layout type. */
    public val bossBarLayoutsRecorded: Int get() = log.count { it.region.name.startsWith("BOSS_BAR") }

    /** Sidebar lines as `key{placeholders}` strings, top to bottom (empty when no sidebar is shown). */
    public fun sidebarText(player: PlayerId): List<String> =
        sidebar(player)?.lines?.map { describe(it) }.orEmpty()

    internal fun attach(bus: IDomainEventBus) {
        bus.subscribe(HudShown::class, EventDelivery.Global) { record(it.playerId, it.layout.region(), Op.SHOW, it.layout) }
        bus.subscribe(HudUpdated::class, EventDelivery.Global) { record(it.playerId, it.layout.region(), Op.UPDATE, it.layout) }
        bus.subscribe(HudCleared::class, EventDelivery.Global) { record(it.playerId, it.region, Op.CLEAR, null) }
    }

    private fun record(player: PlayerId, region: HudRegion, op: Op, layout: HudLayout?) {
        if (layout == null) current.remove(player to region) else current[player to region] = layout
        log += Entry(now(), player, region, op, layout)
        recorder.recordHud(
            now(), player, region.name, op.name.lowercase(), layout?.let(::summary).orEmpty(),
            layout?.let { encodeLayout(it, player) } ?: JsonNull,
        )
    }

    // Resolved at event time, so replay does not depend on later content/i18n changes.
    private fun encodeLayout(layout: HudLayout, player: PlayerId): JsonObject = buildJsonObject {
        fun component(name: String, value: HudComponent?) {
            put(name, value?.let { encodeComponent(it, player) } ?: JsonNull)
        }
        when (layout) {
            is HudLayout.Sidebar -> {
                put("title", messages.render(player, layout.titleKey))
                put("lines", JsonArray(layout.lines.map { encodeComponent(it, player) }))
            }
            is HudLayout.ActionBar -> {
                component("component", layout.component)
                put("ttlTicks", layout.ttlTicks)
            }
            is HudLayout.StatusLine -> {
                component("component", layout.component)
                put("anchorLeftPx", layout.anchorLeftPx?.let(::JsonPrimitive) ?: JsonNull)
            }
            is HudLayout.TabList -> {
                component("header", layout.header)
                component("footer", layout.footer)
            }
            is HudLayout.Subtitle -> {
                component("component", layout.component)
                component("title", layout.title)
                put("fadeInTicks", layout.fadeInTicks)
                put("stayTicks", layout.stayTicks)
                put("fadeOutTicks", layout.fadeOutTicks)
            }
        }
    }

    private fun encodeComponent(value: HudComponent, player: PlayerId): JsonObject = buildJsonObject {
        when (value) {
            is HudComponent.Text -> {
                put("text", messages.render(player, value.messageKey, value.placeholders))
                value.color?.let { put("color", (it.redInt shl 16) or (it.greenInt shl 8) or it.blueInt) }
            }
            is HudComponent.FontText -> {
                put("text", value.text)
                put("font", "astera:default")
                put("widthPx", value.widthPx)
            }
            is HudComponent.Composite -> {
                put("parts", JsonArray(value.parts.map { encodeComponent(it, player) }))
                put("spacing", value.spacing)
            }
            // These intentionally mirror the current PaperHudGateway's visible output.
            is HudComponent.Glyph -> put("text", "<glyph:${value.textureRef.value}>")
            is HudComponent.Spacer -> put("text", " ")
            is HudComponent.ProgressBar -> {
                val filled = value.currentSegments()
                put("text", "[" + "#".repeat(filled) + ".".repeat(value.widthSegments - filled) + "]")
            }
        }
    }

    private fun summary(layout: HudLayout): String = when (layout) {
        is HudLayout.Sidebar -> "${layout.titleKey.value} | " + layout.lines.joinToString(" / ") { describe(it) }
        is HudLayout.ActionBar -> describe(layout.component)
        is HudLayout.TabList -> "tab-list"
        is HudLayout.Subtitle -> describe(layout.component)
        is HudLayout.StatusLine -> describe(layout.component)
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
            is HudComponent.FontText ->
                "hp-bar:" + hpNumbers(component) + (ammoNumbers(component)?.let { " ammo:$it" } ?: "") +
                    effectReadouts(component).joinToString("") { " effect:${it.id}{seconds=${it.seconds},stacks=${it.stacks}}" }
        }

        private fun effectReadouts(text: HudComponent.FontText): List<StatusEffectReadout> {
            val firstIcon = StatusEffectHudGlyphs.icon(StatusEffectHudGlyphs.IDS.first())
            val lastIcon = StatusEffectHudGlyphs.icon(StatusEffectHudGlyphs.IDS.last())
            val icons = text.text.withIndex().filter { it.value in firstIcon..lastIcon }
            return icons.mapIndexed { index, icon ->
                val tail = text.text.substring(icon.index + 1, icons.getOrNull(index + 1)?.index ?: text.text.length)
                fun number(base: Char): Int? = tail.filter { it in base..base + 9 }
                    .map { '0' + (it - base) }.joinToString("").toIntOrNull()
                StatusEffectReadout(
                    StatusEffectHudGlyphs.IDS[icon.value - firstIcon],
                    number(StatusEffectHudGlyphs.digit(0)) ?: 0,
                    number(StatusEffectHudGlyphs.stack(0)) ?: 1,
                )
            }
        }

        private fun digitsOf(glyphs: String): String =
            glyphs.mapNotNull { c ->
                when (c) {
                    StatusHudGlyphs.SLASH -> '/'
                    in StatusHudGlyphs.digit(0)..StatusHudGlyphs.digit(9) -> '0' + (c - StatusHudGlyphs.DIGIT_ZERO)
                    else -> null
                }
            }.joinToString("")

        /** The `cur/max` digits after the bullet icon of a status glyph string, `null` when it has no ammo part. */
        public fun ammoNumbers(text: HudComponent.FontText): String? =
            if (StatusHudGlyphs.AMMO in text.text) digitsOf(text.text.substringAfter(StatusHudGlyphs.AMMO)) else null

        /** The `cur/max` digits of the HP bar of a status glyph string (`?` when none). */
        public fun hpNumbers(text: HudComponent.FontText): String =
            digitsOf(text.text.substringBefore(StatusHudGlyphs.AMMO)).ifEmpty { "?" }
    }
}

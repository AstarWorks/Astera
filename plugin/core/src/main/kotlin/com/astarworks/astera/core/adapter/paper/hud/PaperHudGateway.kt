package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.IScoreboardManager
import com.astarworks.astera.core.adapter.api.hud.ScoreboardId
import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.hud.status.MinecraftTextWidth
import com.astarworks.astera.core.domain.hud.status.NegativeSpace
import com.astarworks.astera.core.domain.hud.status.StatusLineAnchor
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.HudCleared
import com.astarworks.astera.core.domain.event.HudShown
import com.astarworks.astera.core.domain.event.HudUpdated
import com.astarworks.astera.core.domain.event.ResourcePackPushRequested
import com.astarworks.astera.core.domain.hud.HudChannelPolicy
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.region
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Server
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory
import java.time.Duration as JDuration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IHudGateway] (Wave 6b-2, ADR-0031 / ADR-0097).
 *
 * Replaces [com.astarworks.astera.core.application.service.InMemoryHudGateway]
 * at priority 100 on real Paper servers. Maps each [HudLayout] variant to
 * the matching Adventure / Bukkit surface and routes through the bound
 *
 * # Layout routing
 *
 *  - `ActionBar` → `Player.sendActionBar(Component)`. The
 *    vanilla client fades the action bar in ~40 ticks; we re-send on
 *    the TTL boundary (`scheduler.runLater(ticks(ttl - 5), ...)`) when
 *    `ttl > 5` so persistent readouts (mana, cooldown) don't flicker.
 *    On TTL boundary the cleared-handle check matches
 *    [InMemoryHudGateway.scheduleActionBarTtl] — an explicit `updateFor`
 *    / `clearFor` before TTL fires wins.
 *  - `Sidebar` → a per-player line board (`hud.sidebar.<uuid>`) on the shared
 *    [IScoreboardManager]; only changed lines are rewritten (WP14, ADR-0177).
 *  - `TabList` — still owned by `PaperTabList` ([ITabList]); the gateway
 *    only tracks the handle for `activeFor` / `clearFor` accounting.
 *
 * # Channel ownership (ADR-0177)
 *
 * A region holds one handle per player: `showFor` into an occupied region
 * updates that handle in place (no stacked boss bars / sidebars), and a live
 * higher-priority action bar is not overwritten by a lower-priority one
 * ([HudChannelPolicy]).
 *  - `Subtitle` → `Player.showTitle(Title.title(empty, subtitle, Times.times(...)))`.
 *    Main title is empty so this surface is *strictly* a subtitle slot;
 *    callers wanting both main + sub should compose at a higher layer.
 *
 * # Resource pack push
 *
 * `pushResourcePack` calls
 * `Player.setResourcePack(url, sha1HashBytes, required, promptComponent)`
 * — the byte-array hash overload (Paper 1.20.4+) avoids the deprecated
 * `String` hash variant. The `ResourcePackPushRequested` domain event
 * is published so cross-plugin subscribers (audit, analytics, the future
 * `PlayerResourcePackStatusEvent` correlator) stay wired.
 *
 * # Offline player handling
 *
 * Every method first resolves `server.getPlayer(playerId.uuid)`. Offline
 * ⇒ returns `Result.failure(HudError.UnknownPlayer(playerId))` for
 * mutation methods. Matches [PaperCameraGateway]'s offline contract
 * (HUD is meaningless without a connected client). `clearFor` is
 * lenient: an offline player clearing a region still drains the
 * tracking map and publishes `HudCleared` so handle accounting stays
 * consistent for subscribers that observed the prior `HudShown`.
 *
 * # ADR-0149 §C invariant
 *
 * HUD is visual only. This gateway never touches `player.health`,
 * `player.foodLevel`, attribute modifiers, gamemode, or any other
 * gameplay state — those go through the physiology gateway family.
 * The only mutations performed here are Adventure send + scoreboard
 * manager routing. There is no boss-bar surface (F2-2, ADR-0194).
 *
 * # Threading
 *
 * Bukkit `Player.sendActionBar` / `showTitle` / `setResourcePack` are
 * documented main-thread APIs. Off-main callers must round-trip via
 * [scheduler]; the gateway does not auto-marshal (the cost would mask
 * caller-side threading bugs the audit catches in Phase C).
 *
 * @see com.astarworks.astera.core.application.service.InMemoryHudGateway
 *   for the priority-0 default + the shared no-op behaviour contract.
 */
public class PaperHudGateway(
    private val server: Server,
    private val messageRenderer: IMessageRenderer,
    private val scheduler: IScheduler,
    private val scoreboardManager: IScoreboardManager,
    private val eventBus: IDomainEventBus,
) : IHudGateway {

    private data class ActiveHud(
        val playerId: PlayerId,
        val layout: HudLayout,
        /** Tick at which an action bar stops being visible (see [HudChannelPolicy]). */
        val expiresAt: Long = Long.MAX_VALUE,
        /** Bumped on every show / update so a stale TTL callback cannot clear newer content. */
        val gen: Int = 0,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    private val active: ConcurrentHashMap<HudHandle, ActiveHud> = ConcurrentHashMap()

    private val renderFailureLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var unsupportedSidebarLineLogged: Boolean = false
    private var tabListStubLogged: Boolean = false

    private val miniMessage: MiniMessage = MiniMessage.miniMessage()

    init {
        // The client fades an action bar out after ~3 s; the status line (HP) is
        // permanent, so it is re-sent (composited with the live readout) well before that.
        scheduler.runRepeatingTicks(STATUS_REFRESH_TICKS, STATUS_REFRESH_TICKS) { refreshStatusLines() }
    }

    override fun showFor(
        playerId: PlayerId,
        layout: HudLayout,
    ): Result<HudHandle, HudError> {
        val player = server.getPlayer(playerId.uuid)
            ?: return Result.failure(HudError.UnknownPlayer(playerId))

        // One live handle per (player, region): replace in place instead of
        // stacking (a second boss bar per shot, a second sidebar per refresh).
        val now = scheduler.currentTick()
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
        try {
            renderLayout(player, playerId, handle, layout, previous = null)
        } catch (ex: RuntimeException) {
            return Result.failure(renderFailure(layout, ex))
        }
        active[handle] = ActiveHud(playerId, layout, HudChannelPolicy.expiresAt(layout, now), gen = 0)
        if (layout is HudLayout.StatusLine) sendActionBar(player, playerId, liveReadout(playerId))

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
        val player = server.getPlayer(current.playerId.uuid)
            ?: return Result.failure(HudError.UnknownPlayer(current.playerId))

        // If region changed between the old and new layout, clear the old
        // region first so we don't leak an action-bar render
        // outside the handle's lifecycle.
        val sameRegion = current.layout.region() == layout.region()
        if (!sameRegion) {
            clearLayoutSurface(current)
        }
        try {
            renderLayout(
                player, current.playerId, handle, layout,
                previous = if (sameRegion) current.layout else null,
            )
        } catch (ex: RuntimeException) {
            return Result.failure(renderFailure(layout, ex))
        }
        val gen = current.gen + 1
        active[handle] = current.copy(
            layout = layout,
            expiresAt = HudChannelPolicy.expiresAt(layout, scheduler.currentTick()),
            gen = gen,
        )
        if (layout is HudLayout.StatusLine) sendActionBar(player, current.playerId, liveReadout(current.playerId))

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
            val hud = active.remove(handle) ?: continue
            clearLayoutSurface(hud)
        }
        // Region-level clear for surfaces the gateway directly owns —
        // even with no tracked handle, an external caller may have set
        // an action bar via a sibling code path; honour the contract.
        if (matching.isEmpty() && region == HudRegion.ACTION_BAR) {
            server.getPlayer(playerId.uuid)?.let { sendActionBar(it, playerId, readout = null) }
        }
        if (matching.isEmpty() && region == HudRegion.SUBTITLE) {
            server.getPlayer(playerId.uuid)?.resetTitle()
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
        val player = server.getPlayer(playerId.uuid)
            ?: return Result.failure(HudError.UnknownPlayer(playerId))

        val prompt = renderMessageKeyComponent(playerId, pack.promptKey)
        try {
            val hashBytes = hexToBytes(pack.sha1Hash)
            // Paper 1.20.4+ overload — String-hash variant is deprecated.
            player.setResourcePack(pack.url, hashBytes, prompt, pack.required)
        } catch (ex: IllegalArgumentException) {
            // Bukkit throws on malformed URL / hash; the domain init block
            // should have caught both, but mirror to a typed error so
            // callers can branch on the failure surface.
            return Result.failure(HudError.ResourcePackPushFailed(ex.message ?: "invalid pack spec"))
        }
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

    // ---- Layout rendering -------------------------------------------------

    /** A platform exception while drawing used to vanish into the caller's poll; log it once per layout kind. */
    private fun renderFailure(layout: HudLayout, ex: RuntimeException): HudError {
        val kind = layout::class.simpleName ?: "layout"
        if (renderFailureLogged.add(kind)) {
            log.error("HUD render failed for {}; later failures of this kind are not logged.", kind, ex)
        }
        return HudError.RenderFailed("$kind: ${ex.message}")
    }

    /** Render [layout] for [player]. */
    private fun renderLayout(
        player: Player,
        playerId: PlayerId,
        handle: HudHandle,
        layout: HudLayout,
        previous: HudLayout?,
    ) {
        when (layout) {
            is HudLayout.ActionBar -> sendActionBar(player, playerId, layout)
            // Drawn through the action bar once the handle is stored (see showFor / updateFor).
            is HudLayout.StatusLine -> Unit
            is HudLayout.Sidebar -> renderSidebar(playerId, layout, previous as? HudLayout.Sidebar)
            is HudLayout.TabList -> {
                if (!tabListStubLogged) {
                    log.info(
                        "PaperHudGateway.showFor(TabList) is a Phase 1 stub - sub-B " +
                            "PaperTabList owns the tab list surface (ADR-0031 hud-routing). " +
                            "Handle is tracked for activeFor / clearFor accounting only.",
                    )
                    tabListStubLogged = true
                }
            }
            is HudLayout.Subtitle -> {
                val sub = toComponent(playerId, layout.component)
                val times = Title.Times.times(
                    JDuration.ofMillis(layout.fadeInTicks * 50L),
                    JDuration.ofMillis(layout.stayTicks * 50L),
                    JDuration.ofMillis(layout.fadeOutTicks * 50L),
                )
                player.showTitle(Title.title(Component.empty(), sub, times))
            }
        }
    }

    private fun clearLayoutSurface(hud: ActiveHud) {
        val player = server.getPlayer(hud.playerId.uuid) ?: return
        when (hud.layout) {
            // The hud is already out of `active`, so the composition sees neither it nor (for a
            // status line) the line itself: what is left of the action bar is drawn.
            is HudLayout.ActionBar, is HudLayout.StatusLine ->
                sendActionBar(player, hud.playerId, liveReadout(hud.playerId))
            is HudLayout.Sidebar -> {
                val id = sidebarIdFor(hud.playerId)
                scoreboardManager.hide(hud.playerId)
                scoreboardManager.remove(id)
            }
            is HudLayout.TabList -> Unit // owned by PaperTabList
            is HudLayout.Subtitle -> player.resetTitle()
        }
    }

    private fun scheduleActionBarTtl(
        playerId: PlayerId,
        handle: HudHandle,
        layout: HudLayout,
        gen: Int,
    ) {
        if (layout !is HudLayout.ActionBar) return
        val ttl = layout.ttlTicks
        if (ttl <= 0) return
        scheduler.runLater(ticks(ttl.toLong())) {
            // Only the latest show / update of the handle may expire it.
            val current = active[handle] ?: return@runLater
            if (current.playerId != playerId || current.gen != gen) return@runLater
            clearFor(playerId, HudRegion.ACTION_BAR)
        }
    }

    // ---- Action bar + status line (ADR-0182) --------------------------------

    /** The action bar layout currently on screen for [playerId], if any (not yet expired). */
    private fun liveReadout(playerId: PlayerId): HudLayout.ActionBar? {
        val now = scheduler.currentTick()
        return active.values.firstOrNull {
            it.playerId == playerId && it.layout is HudLayout.ActionBar && it.expiresAt > now
        }?.layout as? HudLayout.ActionBar
    }

    private fun statusLineOf(playerId: PlayerId): HudLayout.StatusLine? =
        active.values.firstOrNull { it.playerId == playerId && it.layout is HudLayout.StatusLine }
            ?.layout as? HudLayout.StatusLine

    /** Sends the action bar line: [readout] (or nothing) with the player's status line composited in. */
    private fun sendActionBar(player: Player, playerId: PlayerId, readout: HudLayout.ActionBar?) {
        player.sendActionBar(composeActionBar(playerId, readout, statusLineOf(playerId)))
    }

    private fun refreshStatusLines() {
        val withStatus = active.values.filter { it.layout is HudLayout.StatusLine }.map { it.playerId }.distinct()
        for (playerId in withStatus) {
            val player = server.getPlayer(playerId.uuid) ?: continue
            sendActionBar(player, playerId, liveReadout(playerId))
        }
    }

    /**
     * The action bar is centred on the screen, and the HP block has to sit over
     * the heart row whatever text the readout has. With the readout first, then
     * a gap of `g`, then the block (width `Wh`), the block's left edge lands at
     * `(Wr + g - Wh) / 2` from the centre, so `g = 2 * anchor + Wh - Wr` puts it
     * on [HudLayout.StatusLine.anchorLeftPx]. `Wr` (the readout's width) is an
     * estimate; an error of N px moves the block N / 2 px.
     */
    private fun composeActionBar(
        playerId: PlayerId,
        readout: HudLayout.ActionBar?,
        status: HudLayout.StatusLine?,
    ): Component {
        val readoutComponent = readout?.let { toComponent(playerId, it.component) }
        if (status == null) return readoutComponent ?: Component.empty()
        val block = status.component
        if (block is HudComponent.FontText && status.anchorLeftPx != null) {
            val readoutWidth = readoutComponent
                ?.let { MinecraftTextWidth.estimate(PlainTextComponentSerializer.plainText().serialize(it)) }
                ?: 0
            val gap = StatusLineAnchor.gapPx(status.anchorLeftPx, block.widthPx, readoutWidth)
            var out: Component = Component.empty().append(readoutComponent ?: Component.empty())
            if (gap != 0) out = out.append(fontText(NegativeSpace.spacer(gap)))
            return out.append(fontText(block.text))
        }
        val statusComponent = toComponent(playerId, block)
        return if (readoutComponent == null) statusComponent else statusComponent.append(Component.text("  ")).append(readoutComponent)
    }

    private fun fontText(text: String): Component =
        Component.text(text).font(ASTERA_FONT).color(NamedTextColor.WHITE)

    // ---- Sidebar ----------------------------------------------------------

    /**
     * Sidebar -> the player's own line board. Lines are `Text` components
     * (key + placeholders, localised per viewer by the scoreboard); only lines
     * that differ from [previous] are rewritten, so a once-per-second timer
     * line costs one team-prefix update rather than a full redraw.
     */
    private fun renderSidebar(playerId: PlayerId, layout: HudLayout.Sidebar, previous: HudLayout.Sidebar?) {
        val id = sidebarIdFor(playerId)
        val board = scoreboardManager.scoreboard(id) ?: scoreboardManager.createScoreboard(id, layout.titleKey)
        if (previous == null || previous.titleKey != layout.titleKey) board.setTitle(layout.titleKey)
        val count = layout.lines.size
        layout.lines.forEachIndexed { i, component ->
            val old = previous?.lines?.getOrNull(i)
            if (old == component && previous.lines.size == count) return@forEachIndexed
            val index = count - 1 - i // vanilla sorts by score: highest index renders on top
            if (component is HudComponent.Text) {
                board.addLine(index, component.messageKey, component.placeholders)
            } else {
                if (!unsupportedSidebarLineLogged) {
                    log.warn("Sidebar lines must be HudComponent.Text; {} skipped.", component::class.simpleName)
                    unsupportedSidebarLineLogged = true
                }
                board.removeLine(index)
            }
        }
        if (previous != null && previous.lines.size > count) {
            for (index in count until previous.lines.size) board.removeLine(index)
        }
        if (scoreboardManager.currentScoreboard(playerId)?.id != id) scoreboardManager.show(playerId, board)
    }

    private fun sidebarIdFor(playerId: PlayerId): ScoreboardId = ScoreboardId("hud.sidebar.${playerId.uuid}")

    // ---- HudComponent → Adventure Component -------------------------------

    /**
     * Convert a [HudComponent] tree into an Adventure [Component].
     *
     * Phase 1 PoC scope: Text, Glyph, ProgressBar, Composite, Spacer
     * all map to text-only output. Glyph uses the registered PUA
     * unicode lookup via a future `ICustomFontRegistry` (not wired here
     * — Wave 6b-γ adds the registry); for now we emit
     * `<glyph:ref>` as a debug placeholder so missing glyphs surface
     * visibly during PoC testing.
     */
    private fun toComponent(playerId: PlayerId, component: HudComponent): Component =
        when (component) {
            is HudComponent.Text -> renderTextComponent(playerId, component)
            is HudComponent.FontText -> fontText(component.text)
            is HudComponent.Glyph -> Component.text("<glyph:${component.textureRef.value}>")
            is HudComponent.ProgressBar -> renderProgressBarComponent(playerId, component)
            is HudComponent.Composite -> {
                val parts = component.parts.map { toComponent(playerId, it) }
                val separator = if (component.spacing > 0) {
                    Component.text(" ".repeat(component.spacing.coerceAtMost(8)))
                } else {
                    Component.empty()
                }
                if (separator == Component.empty()) {
                    parts.fold(Component.empty()) { acc, c -> acc.append(c) }
                } else {
                    parts.foldIndexed(Component.empty()) { i, acc, c ->
                        if (i == 0) acc.append(c) else acc.append(separator).append(c)
                    }
                }
            }
            is HudComponent.Spacer -> {
                // Negative-space pixel spacers map to a single space placeholder
                // in PoC; Wave 6b-γ will emit the PUA negative-space glyph
                // (ADR-0097 §custom-font negative spacer).
                Component.text(" ")
            }
        }

    private fun renderTextComponent(playerId: PlayerId, text: HudComponent.Text): Component {
        val rendered = messageRenderer.render(playerId = playerId, key = text.messageKey, placeholders = text.placeholders)
        val base = miniMessage.deserialize(rendered)
        return text.color?.let { base.color(toTextColor(it)) } ?: base
    }

    @Suppress("UnusedParameter") // playerId reserved for per-viewer-locale glyph picks when Wave 6b-γ wires ICustomFontRegistry
    private fun renderProgressBarComponent(playerId: PlayerId, bar: HudComponent.ProgressBar): Component {
        val filled = bar.currentSegments()
        val empty = bar.widthSegments - filled
        // Phase 1 PoC: emit `[####....]` style ASCII placeholder so the
        // layout is testable end-to-end without the font.json glyphs.
        val ascii = buildString {
            append('[')
            repeat(filled) { append('#') }
            repeat(empty) { append('.') }
            append(']')
        }
        return Component.text(ascii)
    }

    private fun renderMessageKeyComponent(playerId: PlayerId, key: MessageKey): Component {
        val rendered = messageRenderer.render(playerId = playerId, key = key)
        return miniMessage.deserialize(rendered)
    }

    private fun toTextColor(color: HudColor): TextColor =
        TextColor.color(color.redInt, color.greenInt, color.blueInt)

    /**
     * Convert the hex-SHA1 (validated by [ResourcePackSpec.init]) to the
     * 20-byte form Paper's `setResourcePack` expects. Throws
     * [IllegalArgumentException] if the input isn't valid hex — the
     * domain init block should have caught this, but defensive parsing
     * keeps the gateway robust against direct caller misuse.
     */
    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 40) { "sha1Hash must be 40 hex chars, was ${hex.length}" }
        val out = ByteArray(20)
        for (i in 0 until 20) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "sha1Hash contains non-hex char at index ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private companion object {
        /** Well under the client's ~3 s action bar fade. */
        const val STATUS_REFRESH_TICKS: Long = 20L

        val ASTERA_FONT: Key = Key.key("astera", "default")
    }
}

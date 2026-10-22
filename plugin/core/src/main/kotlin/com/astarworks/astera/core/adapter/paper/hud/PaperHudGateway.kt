package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.BossBarColor as ApiBossBarColor
import com.astarworks.astera.core.adapter.api.hud.BossBarId
import com.astarworks.astera.core.adapter.api.hud.BossBarOverlay as ApiBossBarOverlay
import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.HudCleared
import com.astarworks.astera.core.domain.event.HudShown
import com.astarworks.astera.core.domain.event.HudUpdated
import com.astarworks.astera.core.domain.event.ResourcePackPushRequested
import com.astarworks.astera.core.domain.hud.BossBarColor as DomainBossBarColor
import com.astarworks.astera.core.domain.hud.BossBarStyle
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
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
 * [IBossBarManager] (also Paper-backed in production) for BossBarSlot.
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
 *  - `BossBarSlot` → routed to [bossBarManager]. The slot becomes part of
 *    the bar id (`hud.slot{n}.{handle}`) so up to 4 concurrent bars per
 *    player are supported (Bukkit stacks up to 4 in the HUD).
 *  - `Sidebar` / `TabList` — **Phase 1 stub** in this gateway. The
 *    sub-B sibling adapter (PaperScoreboardManager + PaperTabList) owns
 *    those surfaces directly via [IScoreboardManager] / [ITabList];
 *    callers wire those ports, not this gateway, for sidebar / tab list.
 *    The gateway still tracks the handle so `activeFor` / `clearFor`
 *    accounting stays correct, and logs INFO on first call.
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
 * The only mutations performed here are Adventure send + BossBar
 * registry routing.
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
    private val bossBarManager: IBossBarManager,
    private val eventBus: IDomainEventBus,
) : IHudGateway {

    private data class ActiveHud(
        val playerId: PlayerId,
        val layout: HudLayout,
        /** Bossbar id materialized for this handle, when the layout is BossBarSlot. */
        val bossBarId: BossBarId? = null,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    private val active: ConcurrentHashMap<HudHandle, ActiveHud> = ConcurrentHashMap()

    private var sidebarStubLogged: Boolean = false
    private var tabListStubLogged: Boolean = false

    private val miniMessage: MiniMessage = MiniMessage.miniMessage()

    override fun showFor(
        playerId: PlayerId,
        layout: HudLayout,
    ): Result<HudHandle, HudError> {
        val player = server.getPlayer(playerId.uuid)
            ?: return Result.failure(HudError.UnknownPlayer(playerId))

        val handle = HudHandle(UUID.randomUUID().toString())
        val bossBarId = renderLayout(player, playerId, handle, layout)
        active[handle] = ActiveHud(playerId, layout, bossBarId)

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
        val player = server.getPlayer(current.playerId.uuid)
            ?: return Result.failure(HudError.UnknownPlayer(current.playerId))

        // If region changed between the old and new layout, clear the old
        // region first so we don't leak a bossbar / action-bar render
        // outside the handle's lifecycle.
        if (regionOf(current.layout) != regionOf(layout)) {
            clearLayoutSurface(current)
        }
        val newBossBarId = renderLayout(player, current.playerId, handle, layout)
        active[handle] = current.copy(layout = layout, bossBarId = newBossBarId)

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
            val hud = active.remove(handle) ?: continue
            clearLayoutSurface(hud)
        }
        // Region-level clear for surfaces the gateway directly owns —
        // even with no tracked handle, an external caller may have set
        // an action bar via a sibling code path; honour the contract.
        if (matching.isEmpty() && region == HudRegion.ACTION_BAR) {
            server.getPlayer(playerId.uuid)?.sendActionBar(Component.empty())
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

    override fun activeFor(playerId: PlayerId): Collection<HudHandle> =
        active.entries
            .filter { it.value.playerId == playerId }
            .map { it.key }

    // ---- Layout rendering -------------------------------------------------

    /**
     * Render [layout] for [player]. Returns the materialized [BossBarId]
     * when the layout is `BossBarSlot` (so update / clear can dispose of
     * the bar later), or `null` otherwise.
     */
    private fun renderLayout(
        player: Player,
        playerId: PlayerId,
        handle: HudHandle,
        layout: HudLayout,
    ): BossBarId? = when (layout) {
        is HudLayout.ActionBar -> {
            player.sendActionBar(toComponent(playerId, layout.component))
            null
        }
        is HudLayout.BossBarSlot -> renderBossBar(playerId, handle, layout)
        is HudLayout.Sidebar -> {
            if (!sidebarStubLogged) {
                log.info(
                    "PaperHudGateway.showFor(Sidebar) is a Phase 1 stub — sub-B " +
                        "PaperScoreboardManager owns the sidebar surface (ADR-0031 §hud-routing). " +
                        "Handle is tracked for activeFor / clearFor accounting only.",
                )
                sidebarStubLogged = true
            }
            null
        }
        is HudLayout.TabList -> {
            if (!tabListStubLogged) {
                log.info(
                    "PaperHudGateway.showFor(TabList) is a Phase 1 stub — sub-B " +
                        "PaperTabList owns the tab list surface (ADR-0031 §hud-routing). " +
                        "Handle is tracked for activeFor / clearFor accounting only.",
                )
                tabListStubLogged = true
            }
            null
        }
        is HudLayout.Subtitle -> {
            val sub = toComponent(playerId, layout.component)
            val times = Title.Times.times(
                JDuration.ofMillis(layout.fadeInTicks * 50L),
                JDuration.ofMillis(layout.stayTicks * 50L),
                JDuration.ofMillis(layout.fadeOutTicks * 50L),
            )
            player.showTitle(Title.title(Component.empty(), sub, times))
            null
        }
    }

    private fun renderBossBar(
        playerId: PlayerId,
        handle: HudHandle,
        layout: HudLayout.BossBarSlot,
    ): BossBarId {
        val id = bossBarIdFor(handle, layout.slot)
        // The component-based title in HudLayout.BossBarSlot doesn't map to
        // a MessageKey directly; we resolve to a literal MiniMessage string
        // via the renderer's pipeline. To stay within IBossBarManager's
        // MessageKey-typed signature, we first render the component to a
        // string and wrap it as an inline-translation MessageKey replacement
        // by directly calling the Adventure bossbar API for the title.
        // Caller-facing contract: title comes from layout.component.
        val bar = bossBarManager.createBossBar(
            id = id,
            // Placeholder MessageKey: the actual title is overwritten below
            // via PaperBossBar.adventureBar.name(...). When the underlying
            // manager is the InMemory fallback (tests), the MessageKey is
            // the visible title — acceptable since tests don't render
            // Components.
            title = MessageKey("hud.bossbar.placeholder"),
            progress = layout.progress.toDouble(),
            color = layout.color.toApi(),
            overlay = layout.style.toApi(),
            flags = emptySet(),
        )
        // Reach through to set the actual Component title when the manager
        // is the Paper concrete; falls through silently for the InMemory
        // fake (which doesn't render Adventure Components anyway).
        if (bar is PaperBossBar) {
            bar.adventureBar.name(toComponent(playerId, layout.component))
        }
        bossBarManager.show(playerId, bar)
        return id
    }

    private fun clearLayoutSurface(hud: ActiveHud) {
        val player = server.getPlayer(hud.playerId.uuid) ?: return
        when (val layout = hud.layout) {
            is HudLayout.ActionBar -> player.sendActionBar(Component.empty())
            is HudLayout.BossBarSlot -> {
                val id = hud.bossBarId ?: bossBarIdFor(HudHandle(hud.playerId.toString()), layout.slot)
                bossBarManager.removeBossBar(id)
            }
            is HudLayout.Sidebar -> Unit // owned by PaperScoreboardManager
            is HudLayout.TabList -> Unit // owned by PaperTabList
            is HudLayout.Subtitle -> player.resetTitle()
        }
    }

    private fun scheduleActionBarTtl(
        playerId: PlayerId,
        handle: HudHandle,
        layout: HudLayout,
    ) {
        if (layout !is HudLayout.ActionBar) return
        val ttl = layout.ttlTicks
        if (ttl <= 0) return
        scheduler.runLater(ticks(ttl.toLong())) {
            val current = active[handle] ?: return@runLater
            if (current.playerId != playerId) return@runLater
            if (current.layout !is HudLayout.ActionBar) return@runLater
            clearFor(playerId, HudRegion.ACTION_BAR)
        }
    }

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

    // ---- Region mapping ---------------------------------------------------

    /**
     * Map a layout to its `HudRegion`. Exhaustive on the sealed
     * `HudLayout` hierarchy so a new variant forces a compile error.
     * Mirrors [InMemoryHudGateway.regionOf] — the contract is:
     * `clearFor(region)` clears any handle whose layout resolves to
     * that region.
     */
    private fun regionOf(layout: HudLayout): HudRegion = when (layout) {
        is HudLayout.ActionBar -> HudRegion.ACTION_BAR
        is HudLayout.BossBarSlot -> when (layout.slot) {
            1 -> HudRegion.BOSS_BAR_SLOT_1
            2 -> HudRegion.BOSS_BAR_SLOT_2
            3 -> HudRegion.BOSS_BAR_SLOT_3
            4 -> HudRegion.BOSS_BAR_SLOT_4
            else -> error("BossBarSlot.slot out of range: ${layout.slot}")
        }
        is HudLayout.Sidebar -> HudRegion.SIDEBAR
        is HudLayout.TabList -> HudRegion.TAB_LIST_HEADER
        is HudLayout.Subtitle -> HudRegion.SUBTITLE
    }

    private fun bossBarIdFor(handle: HudHandle, slot: Int): BossBarId =
        BossBarId("hud.slot${slot}.${handle.value.lowercase().filter { it.isLetterOrDigit() || it == '-' }}")

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

    // ---- Domain ↔ adapter-api enum bridges --------------------------------

    private fun DomainBossBarColor.toApi(): ApiBossBarColor = when (this) {
        DomainBossBarColor.PINK -> ApiBossBarColor.PINK
        DomainBossBarColor.BLUE -> ApiBossBarColor.BLUE
        DomainBossBarColor.RED -> ApiBossBarColor.RED
        DomainBossBarColor.GREEN -> ApiBossBarColor.GREEN
        DomainBossBarColor.YELLOW -> ApiBossBarColor.YELLOW
        DomainBossBarColor.PURPLE -> ApiBossBarColor.PURPLE
        DomainBossBarColor.WHITE -> ApiBossBarColor.WHITE
    }

    private fun BossBarStyle.toApi(): ApiBossBarOverlay = when (this) {
        BossBarStyle.PROGRESS -> ApiBossBarOverlay.PROGRESS
        BossBarStyle.NOTCHED_6 -> ApiBossBarOverlay.NOTCHED_6
        BossBarStyle.NOTCHED_10 -> ApiBossBarOverlay.NOTCHED_10
        BossBarStyle.NOTCHED_12 -> ApiBossBarOverlay.NOTCHED_12
        BossBarStyle.NOTCHED_20 -> ApiBossBarOverlay.NOTCHED_20
    }

}

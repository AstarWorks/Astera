package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.BossBarColor
import com.astarworks.astera.core.adapter.api.hud.BossBarFlag
import com.astarworks.astera.core.adapter.api.hud.BossBarId
import com.astarworks.astera.core.adapter.api.hud.BossBarOverlay
import com.astarworks.astera.core.adapter.api.hud.IBossBar
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IBossBar] (Wave 6b-2, ADR-0031 / ADR-0097).
 *
 * Wraps an Adventure `BossBar` and routes viewer mutation through
 * `Player#showBossBar` / `Player#hideBossBar`. Mutating [title] /
 * [progress] / [color] / [overlay] / flag set goes directly through the
 * Adventure bar — Adventure's internal listener machinery pushes the
 * change to every current viewer, so the manager doesn't need to fan
 * out updates manually.
 *
 * # i18n model (Phase 1 PoC)
 *
 * Adventure's `BossBar.name(Component)` takes one Component, but the
 * domain stores a [MessageKey] that resolves per-viewer locale. For
 * Phase 1 we resolve the title with `playerId = null` (default-locale
 * fallback in [IMessageRenderer]) so every viewer sees the same string.
 * Per-viewer localized titles need a per-player `BossBar` clone, which
 * is a Wave 6b-_polish concern — recorded as a stub log on first call.
 *
 * # Viewer state
 *
 * `_viewers` mirrors the set of players currently subscribed to the
 * underlying Adventure bar. The mirror exists because Adventure's
 * `BossBar.viewers()` returns an `Iterable<Audience>` of opaque audiences
 * — we can't recover [PlayerId] from those. Keeping our own
 * [ConcurrentHashMap] (used as a set) means `currentBars(playerId)` is
 * O(1) per bar at the manager level.
 *
 * # Offline viewer handling
 *
 * If [addViewer] / [removeViewer] is called for a player who has
 * disconnected between scheduling and execution, the lookup returns
 * `null` and we silently no-op + DEBUG log. No exception, no failure —
 * matches the [com.astarworks.astera.core.adapter.paper.physiology.PaperPlayerSpeedGateway]
 * record-and-skip pattern (ADR-0144 family).
 *
 * # Threading
 *
 * Adventure permits cross-thread mutation of `BossBar` and audience
 * registration (it dispatches packet writes internally), but
 * `Server.getPlayer` and `Player.showBossBar` are documented main-thread
 * APIs. Callers off the main thread must round-trip through
 * `IScheduler`. The manager's contract documents this.
 */
public class PaperBossBar internal constructor(
    override val id: BossBarId,
    initialTitle: MessageKey,
    initialProgress: Double,
    initialColor: BossBarColor,
    initialOverlay: BossBarOverlay,
    initialFlags: Set<BossBarFlag>,
    private val server: Server,
    private val messageRenderer: IMessageRenderer?,
) : IBossBar {

    init {
        require(initialProgress in 0.0..1.0) {
            "BossBar progress must be in [0.0, 1.0]: $initialProgress"
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** Underlying Adventure bar. Single instance shared by every viewer. */
    internal val adventureBar: BossBar = BossBar.bossBar(
        renderTitleComponent(initialTitle),
        initialProgress.toFloat(),
        toAdventureColor(initialColor),
        toAdventureOverlay(initialOverlay),
        initialFlags.mapTo(mutableSetOf()) { toAdventureFlag(it) },
    )

    /**
     * Viewer mirror — see class KDoc §"Viewer state". Used as a set;
     * value is `Unit` placeholder so we can use [ConcurrentHashMap].
     */
    private val _viewers: ConcurrentHashMap<PlayerId, Unit> = ConcurrentHashMap()

    private var _title: MessageKey = initialTitle
    override var title: MessageKey
        get() = _title
        set(value) {
            _title = value
            adventureBar.name(renderTitleComponent(value))
        }

    private var _progress: Double = initialProgress
    override var progress: Double
        get() = _progress
        set(value) {
            require(value in 0.0..1.0) {
                "BossBar progress must be in [0.0, 1.0]: $value"
            }
            _progress = value
            adventureBar.progress(value.toFloat())
        }

    private var _color: BossBarColor = initialColor
    override var color: BossBarColor
        get() = _color
        set(value) {
            _color = value
            adventureBar.color(toAdventureColor(value))
        }

    private var _overlay: BossBarOverlay = initialOverlay
    override var overlay: BossBarOverlay
        get() = _overlay
        set(value) {
            _overlay = value
            adventureBar.overlay(toAdventureOverlay(value))
        }

    override val flags: Set<BossBarFlag>
        get() = adventureBar.flags().mapTo(mutableSetOf()) { fromAdventureFlag(it) }

    override fun addFlag(flag: BossBarFlag) {
        adventureBar.addFlag(toAdventureFlag(flag))
    }

    override fun removeFlag(flag: BossBarFlag) {
        adventureBar.removeFlag(toAdventureFlag(flag))
    }

    /**
     * Attach [playerId] as a viewer. Idempotent: re-adding an existing
     * viewer is a no-op. Offline players are silently skipped with a
     * DEBUG log (see class KDoc §"Offline viewer handling").
     */
    internal fun addViewer(playerId: PlayerId) {
        if (_viewers.containsKey(playerId)) return
        val player = server.getPlayer(playerId.uuid)
        if (player == null) {
            log.debug(
                "PaperBossBar.addViewer skipped: player {} is offline (bar={})",
                playerId, id,
            )
            return
        }
        _viewers[playerId] = Unit
        player.showBossBar(adventureBar)
    }

    /**
     * Detach [playerId] as a viewer. Idempotent: removing a non-viewer
     * is a no-op. If the player has gone offline since [addViewer],
     * we still drop them from the mirror — Adventure auto-cleans
     * audience subscriptions on disconnect, so the Bukkit-side hide
     * call is harmless and elided.
     */
    internal fun removeViewer(playerId: PlayerId) {
        if (_viewers.remove(playerId) == null) return
        val player = server.getPlayer(playerId.uuid)
        if (player == null) {
            log.debug(
                "PaperBossBar.removeViewer: player {} already offline; mirror only (bar={})",
                playerId, id,
            )
            return
        }
        player.hideBossBar(adventureBar)
    }

    /**
     * Hide the bar from every current viewer and drop the mirror.
     * Called by [PaperBossBarManager.removeBossBar] before unregistering.
     */
    internal fun removeAllViewers() {
        val snapshot = _viewers.keys.toList()
        _viewers.clear()
        for (pid in snapshot) {
            server.getPlayer(pid.uuid)?.hideBossBar(adventureBar)
        }
    }

    /** Snapshot of currently-attached viewers (test + manager affordance). */
    internal fun viewerSnapshot(): Set<PlayerId> = _viewers.keys.toSet()

    /**
     * Has [playerId] been attached as a viewer? O(1).
     * Used by the manager's `currentBars(playerId)` and `hideAll`.
     */
    internal fun hasViewer(playerId: PlayerId): Boolean = _viewers.containsKey(playerId)

    /**
     * Resolve the title MessageKey to an Adventure Component.
     *
     * Phase 1 PoC: locale-neutral resolution via `playerId = null`
     * (the renderer falls back to default locale). Per-viewer locale
     * needs a per-player bar clone; documented in class KDoc.
     *
     * When [messageRenderer] is null (test wiring), falls back to
     * `Component.text(key.value)` so the title is at least debuggable.
     */
    private fun renderTitleComponent(key: MessageKey): Component {
        val renderer = messageRenderer
            ?: return Component.text(key.value)
        val rendered = renderer.render(playerId = null, key = key)
        // MiniMessage parse — the existing AsteraTextDeserializer handles
        // both MiniMessage and legacy &-codes, but we only have a String
        // here and no need for legacy detection (i18n strings should be
        // MiniMessage by convention). Direct MiniMessage parse keeps the
        // dependency surface minimal.
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
            .deserialize(rendered)
    }

    private fun toAdventureColor(color: BossBarColor): BossBar.Color = when (color) {
        BossBarColor.PINK -> BossBar.Color.PINK
        BossBarColor.BLUE -> BossBar.Color.BLUE
        BossBarColor.RED -> BossBar.Color.RED
        BossBarColor.GREEN -> BossBar.Color.GREEN
        BossBarColor.YELLOW -> BossBar.Color.YELLOW
        BossBarColor.PURPLE -> BossBar.Color.PURPLE
        BossBarColor.WHITE -> BossBar.Color.WHITE
    }

    private fun toAdventureOverlay(overlay: BossBarOverlay): BossBar.Overlay = when (overlay) {
        BossBarOverlay.PROGRESS -> BossBar.Overlay.PROGRESS
        BossBarOverlay.NOTCHED_6 -> BossBar.Overlay.NOTCHED_6
        BossBarOverlay.NOTCHED_10 -> BossBar.Overlay.NOTCHED_10
        BossBarOverlay.NOTCHED_12 -> BossBar.Overlay.NOTCHED_12
        BossBarOverlay.NOTCHED_20 -> BossBar.Overlay.NOTCHED_20
    }

    private fun toAdventureFlag(flag: BossBarFlag): BossBar.Flag = when (flag) {
        BossBarFlag.DARKEN_SCREEN -> BossBar.Flag.DARKEN_SCREEN
        BossBarFlag.PLAY_BOSS_MUSIC -> BossBar.Flag.PLAY_BOSS_MUSIC
        BossBarFlag.CREATE_WORLD_FOG -> BossBar.Flag.CREATE_WORLD_FOG
    }

    private fun fromAdventureFlag(flag: BossBar.Flag): BossBarFlag = when (flag) {
        BossBar.Flag.DARKEN_SCREEN -> BossBarFlag.DARKEN_SCREEN
        BossBar.Flag.PLAY_BOSS_MUSIC -> BossBarFlag.PLAY_BOSS_MUSIC
        BossBar.Flag.CREATE_WORLD_FOG -> BossBarFlag.CREATE_WORLD_FOG
    }

    /**
     * Internal replace-flags shortcut used by [PaperBossBarManager.createBossBar]
     * when the bar already exists with the same id (id-idempotency clause
     * in [com.astarworks.astera.core.adapter.api.hud.IBossBarManager.createBossBar]).
     */
    internal fun replaceFlags(next: Set<BossBarFlag>) {
        val current = adventureBar.flags()
        for (existing in current) {
            adventureBar.removeFlag(existing)
        }
        for (added in next) {
            adventureBar.addFlag(toAdventureFlag(added))
        }
    }
}

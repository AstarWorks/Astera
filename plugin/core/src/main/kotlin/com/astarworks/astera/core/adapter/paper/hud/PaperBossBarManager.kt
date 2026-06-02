package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.BossBarColor
import com.astarworks.astera.core.adapter.api.hud.BossBarFlag
import com.astarworks.astera.core.adapter.api.hud.BossBarId
import com.astarworks.astera.core.adapter.api.hud.BossBarOverlay
import com.astarworks.astera.core.adapter.api.hud.IBossBar
import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Paper concrete of [IBossBarManager] (Wave 6b-2, ADR-0031 / ADR-0097).
 *
 * Wraps a registry of [PaperBossBar] instances, each backed by an
 * Adventure `BossBar`. Show / hide / hideAll route to the per-bar
 * [PaperBossBar.addViewer] / [PaperBossBar.removeViewer] which call
 * `Player#showBossBar` / `Player#hideBossBar` after an online check.
 *
 * # Registration
 *
 * `AsteraCorePlugin.onEnable` registers at priority 100, shadowing
 * [com.astarworks.astera.core.application.service.InMemoryBossBarManager]
 * (priority 0) on real Paper servers. The InMemory impl stays the
 * test-default; the Paper impl is server-only.
 *
 * # Threading
 *
 * Inherits [PaperBossBar]'s contract: main-thread for viewer mutation.
 * Use [scheduler] to round-trip if you're off-main. The internal
 * [ConcurrentHashMap] is precautionary for async listeners that call
 * `bossBar(id)` to look up a bar — registry reads are safe off-main.
 *
 * # i18n
 *
 * The optional [messageRenderer] is forwarded to each created bar so
 * the title MessageKey can resolve to a real localized Component. When
 * null (test wiring), [PaperBossBar] falls back to literal-key rendering.
 *
 * @see PaperBossBar for the per-bar wrapper.
 * @see com.astarworks.astera.core.application.service.InMemoryBossBarManager
 *   for the priority-0 default.
 */
public class PaperBossBarManager(
    private val server: Server,
    @Suppress("UnusedPrivateProperty") // scheduler kept for symmetry with sibling adapters and Wave 6b-_polish per-viewer locale work
    private val scheduler: IScheduler,
    private val messageRenderer: IMessageRenderer? = null,
) : IBossBarManager {

    private val log = LoggerFactory.getLogger(javaClass)

    private val bars: ConcurrentHashMap<BossBarId, PaperBossBar> = ConcurrentHashMap()

    override fun createBossBar(
        id: BossBarId,
        title: MessageKey,
        progress: Double,
        color: BossBarColor,
        overlay: BossBarOverlay,
        flags: Set<BossBarFlag>,
    ): IBossBar {
        val existing = bars[id]
        if (existing != null) {
            // Id-idempotent (per IBossBarManager.createBossBar contract):
            // mutate-in-place so existing viewers stay attached and see
            // the new fields via Adventure's listener machinery.
            existing.title = title
            existing.progress = progress
            existing.color = color
            existing.overlay = overlay
            existing.replaceFlags(flags)
            return existing
        }
        val created = PaperBossBar(
            id = id,
            initialTitle = title,
            initialProgress = progress,
            initialColor = color,
            initialOverlay = overlay,
            initialFlags = flags,
            server = server,
            messageRenderer = messageRenderer,
        )
        bars[id] = created
        return created
    }

    override fun removeBossBar(id: BossBarId) {
        val removed = bars.remove(id) ?: return
        removed.removeAllViewers()
    }

    override fun bossBar(id: BossBarId): IBossBar? = bars[id]

    override fun show(playerId: PlayerId, bar: IBossBar) {
        val paperBar = resolvePaperBar(bar) ?: return
        paperBar.addViewer(playerId)
    }

    override fun hide(playerId: PlayerId, bar: IBossBar) {
        val paperBar = resolvePaperBar(bar) ?: return
        paperBar.removeViewer(playerId)
    }

    override fun hideAll(playerId: PlayerId) {
        for (bar in bars.values) {
            if (bar.hasViewer(playerId)) {
                bar.removeViewer(playerId)
            }
        }
    }

    override fun currentBars(playerId: PlayerId): List<IBossBar> {
        // Insertion order matches the registry's ConcurrentHashMap iteration
        // (not strictly ordered, but stable per JVM run). Callers needing
        // deterministic display order should sort by BossBarId.
        return bars.values.filter { it.hasViewer(playerId) }
    }

    /**
     * Bridge an external [IBossBar] back to our concrete. If the bar
     * was created by a *different* manager (e.g. someone passes an
     * InMemoryBossBar's IBossBar to this Paper manager), we cannot
     * route it through Adventure — log WARN and no-op rather than
     * fail loudly, since cross-manager usage is a wiring bug the
     * priority-based ServiceRegistry should prevent.
     */
    private fun resolvePaperBar(bar: IBossBar): PaperBossBar? {
        if (bar is PaperBossBar) return bar
        log.warn(
            "PaperBossBarManager received IBossBar not created here: id={}, type={}. " +
                "Verify ServiceRegistry wiring — Paper manager should own every bar in production.",
            bar.id,
            bar::class.qualifiedName,
        )
        return null
    }
}

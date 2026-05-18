package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.adapter.api.hud.BossBarColor
import com.astarworks.astera.core.adapter.api.hud.BossBarFlag
import com.astarworks.astera.core.adapter.api.hud.BossBarId
import com.astarworks.astera.core.adapter.api.hud.BossBarOverlay
import com.astarworks.astera.core.adapter.api.hud.IBossBar
import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IBossBarManager] for use case tests.
 *
 * Stores bars in a registry and tracks per-player visibility in a separate
 * map so tests can assert "this player sees these bars" without standing
 * up a Paper server. Production-quality enough that feature tests can
 * exercise the full HUD flow (create → show → mutate → hide).
 *
 * Thread-agnostic by design — domain/application tests don't simulate the
 * Adventure main-thread requirement.
 */
public class FakeBossBarManager : IBossBarManager {

    private val bars: MutableMap<BossBarId, FakeBossBar> = linkedMapOf()
    private val visibility: MutableMap<PlayerId, MutableSet<BossBarId>> = linkedMapOf()

    /** Snapshot of currently-registered bars, keyed by id. For test readability. */
    public val activeBars: Map<BossBarId, IBossBar>
        get() = bars.toMap()

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
            existing.title = title
            existing.progress = progress
            existing.color = color
            existing.overlay = overlay
            existing.replaceFlags(flags)
            return existing
        }
        val created = FakeBossBar(
            id = id,
            initialTitle = title,
            initialProgress = progress,
            initialColor = color,
            initialOverlay = overlay,
            initialFlags = flags,
        )
        bars[id] = created
        return created
    }

    override fun removeBossBar(id: BossBarId) {
        if (bars.remove(id) == null) return
        for (visible in visibility.values) {
            visible.remove(id)
        }
    }

    override fun bossBar(id: BossBarId): IBossBar? = bars[id]

    override fun show(playerId: PlayerId, bar: IBossBar) {
        val viewerSet = visibility.getOrPut(playerId) { linkedSetOf() }
        viewerSet.add(bar.id)
    }

    override fun hide(playerId: PlayerId, bar: IBossBar) {
        visibility[playerId]?.remove(bar.id)
    }

    override fun hideAll(playerId: PlayerId) {
        visibility[playerId]?.clear()
    }

    override fun currentBars(playerId: PlayerId): List<IBossBar> {
        val visible = visibility[playerId] ?: return emptyList()
        return visible.mapNotNull { bars[it] }
    }

    /**
     * In-memory [IBossBar]. Progress setter enforces `[0.0, 1.0]` matching
     * the interface contract; other setters are unrestricted.
     */
    public class FakeBossBar internal constructor(
        override val id: BossBarId,
        initialTitle: MessageKey,
        initialProgress: Double,
        initialColor: BossBarColor,
        initialOverlay: BossBarOverlay,
        initialFlags: Set<BossBarFlag>,
    ) : IBossBar {

        init {
            require(initialProgress in 0.0..1.0) {
                "BossBar progress must be in [0.0, 1.0]: $initialProgress"
            }
        }

        override var title: MessageKey = initialTitle
        override var color: BossBarColor = initialColor
        override var overlay: BossBarOverlay = initialOverlay

        private var _progress: Double = initialProgress
        override var progress: Double
            get() = _progress
            set(value) {
                require(value in 0.0..1.0) {
                    "BossBar progress must be in [0.0, 1.0]: $value"
                }
                _progress = value
            }

        private val _flags: MutableSet<BossBarFlag> = initialFlags.toMutableSet()
        override val flags: Set<BossBarFlag>
            get() = _flags.toSet()

        override fun addFlag(flag: BossBarFlag) {
            _flags.add(flag)
        }

        override fun removeFlag(flag: BossBarFlag) {
            _flags.remove(flag)
        }

        internal fun replaceFlags(next: Set<BossBarFlag>) {
            _flags.clear()
            _flags.addAll(next)
        }
    }
}

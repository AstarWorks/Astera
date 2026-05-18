package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.adapter.api.hud.BossBarColor
import com.astarworks.astera.core.adapter.api.hud.BossBarFlag
import com.astarworks.astera.core.adapter.api.hud.BossBarId
import com.astarworks.astera.core.adapter.api.hud.BossBarOverlay
import com.astarworks.astera.core.adapter.api.hud.IBossBar
import com.astarworks.astera.core.adapter.api.hud.IBossBarManager
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5b default [IBossBarManager] registered by Core at priority 0.
 *
 * Wire-compatible with the Wave 6 Paper impl (which registers at priority
 * 100 and supersedes this one). Existing in-process callers — match HUD,
 * Tutorial — see no behavioural difference; the only visible delta is that
 * Paper actually pushes the bar to clients.
 *
 * Maintains a registry of bars by id plus a per-player visibility set.
 * Multiple bars per player are allowed (Adventure permits 0..N concurrent
 * bossbars per viewer; Bedrock clamps to 4 but Java is unconstrained).
 */
public class InMemoryBossBarManager : IBossBarManager {

    private val bars: MutableMap<BossBarId, InMemoryBossBar> = linkedMapOf()
    private val visibility: MutableMap<PlayerId, MutableSet<BossBarId>> = linkedMapOf()

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
        val created = InMemoryBossBar(id, title, progress, color, overlay, flags)
        bars[id] = created
        return created
    }

    override fun removeBossBar(id: BossBarId) {
        if (bars.remove(id) == null) return
        for (viewers in visibility.values) viewers.remove(id)
    }

    override fun bossBar(id: BossBarId): IBossBar? = bars[id]

    override fun show(playerId: PlayerId, bar: IBossBar) {
        visibility.getOrPut(playerId) { linkedSetOf() }.add(bar.id)
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

    /** In-memory [IBossBar]. Progress setter enforces the `[0, 1]` contract. */
    private class InMemoryBossBar(
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

        override fun addFlag(flag: BossBarFlag) { _flags.add(flag) }
        override fun removeFlag(flag: BossBarFlag) { _flags.remove(flag) }

        fun replaceFlags(next: Set<BossBarFlag>) {
            _flags.clear()
            _flags.addAll(next)
        }
    }
}

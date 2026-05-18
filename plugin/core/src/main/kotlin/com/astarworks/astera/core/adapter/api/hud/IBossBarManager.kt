package com.astarworks.astera.core.adapter.api.hud

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Vendor-neutral registry + viewer router for [IBossBar].
 *
 * Per ADR-0001/0002 this lives in `core.adapter.api` so feature plugins
 * (Match HUD, Combat HP bars, XP progress) can compose bossbar UI without
 * touching Bukkit / Adventure. Paper implementation arrives Wave 6
 * (ADR-0031); the in-memory [com.astarworks.astera.testfixtures.FakeBossBarManager]
 * covers application-layer tests today.
 *
 * **Ownership model.** The manager owns the bar registry and the
 * per-player visibility set. Callers hold an [IBossBar] reference and
 * mutate fields directly; the manager observes nothing — implementations
 * are expected to push updates to viewers on every field change via
 * their own wiring (the Paper impl backs each [IBossBar] with an
 * Adventure `BossBar`, which already notifies viewers).
 *
 * **Threading.** Same as [IBossBar] — main thread only in production
 * Paper. The fake is thread-agnostic.
 */
public interface IBossBarManager {

    /**
     * Create (or replace-fields-on) a bossbar with the given [id].
     *
     * **Idempotent on id.** If a bar with [id] already exists, its
     * current instance is returned with [title], [progress], [color],
     * [overlay], and [flags] overwritten. The same `IBossBar` reference
     * stays valid and its existing viewers continue to see it (now
     * showing the new fields). Callers that want a fresh bar must
     * [removeBossBar] first.
     *
     * @param progress fill ratio in `[0.0, 1.0]`; throws on out-of-range.
     */
    public fun createBossBar(
        id: BossBarId,
        title: MessageKey,
        progress: Double = 1.0,
        color: BossBarColor = BossBarColor.WHITE,
        overlay: BossBarOverlay = BossBarOverlay.PROGRESS,
        flags: Set<BossBarFlag> = emptySet(),
    ): IBossBar

    /**
     * Hide [id] from every current viewer and drop it from the registry.
     * No-op if no bar with [id] is registered.
     */
    public fun removeBossBar(id: BossBarId)

    /** Lookup by id. Returns `null` if no bar with [id] is registered. */
    public fun bossBar(id: BossBarId): IBossBar?

    /**
     * Show [bar] to [playerId]. A player can have multiple bars visible
     * simultaneously (Adventure stacks them in the HUD).
     *
     * No-op if the bar is already visible to this player.
     */
    public fun show(playerId: PlayerId, bar: IBossBar)

    /**
     * Hide [bar] from [playerId] only. Other bars currently shown to the
     * same player are unaffected, and other viewers of [bar] keep seeing
     * it. No-op if [bar] isn't currently shown to [playerId].
     */
    public fun hide(playerId: PlayerId, bar: IBossBar)

    /** Hide every bar currently visible to [playerId]. No-op if none. */
    public fun hideAll(playerId: PlayerId)

    /**
     * Snapshot of bars currently visible to [playerId], in insertion
     * order. The returned list is a copy — mutating it does not affect
     * visibility.
     */
    public fun currentBars(playerId: PlayerId): List<IBossBar>
}

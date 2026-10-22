package com.astarworks.astera.core.domain.hud

/**
 * The screen region a layout occupies. A region is a **channel with exactly
 * one live handle per player** (WP14, ADR-0177): a second `showFor` into an
 * occupied region replaces the first instead of stacking next to it.
 *
 * Exhaustive on the sealed [HudLayout] hierarchy so a new variant is a
 * compile error here, not a silent "clear did not find my handle".
 */
public fun HudLayout.region(): HudRegion = when (this) {
    is HudLayout.ActionBar -> HudRegion.ACTION_BAR
    is HudLayout.BossBarSlot -> when (slot) {
        1 -> HudRegion.BOSS_BAR_SLOT_1
        2 -> HudRegion.BOSS_BAR_SLOT_2
        3 -> HudRegion.BOSS_BAR_SLOT_3
        4 -> HudRegion.BOSS_BAR_SLOT_4
        else -> error("BossBarSlot.slot out of range: $slot") // init-block rejects this
    }
    is HudLayout.Sidebar -> HudRegion.SIDEBAR
    // Header and footer share one logical layout; clearing either clears it.
    is HudLayout.TabList -> HudRegion.TAB_LIST_HEADER
    is HudLayout.Subtitle -> HudRegion.SUBTITLE
}

/**
 * Arbitration rules shared by every [com.astarworks.astera.core.application.port.outbound.IHudGateway]
 * implementation, so the in-memory, fake and Paper gateways cannot drift.
 *
 * The only region with more than one producer is the action bar: a persistent
 * readout (cooldowns, priority 0) and short toasts (currency, rejected cast,
 * priority >= 1). A toast must not be erased by the next readout refresh, so a
 * live higher-priority action bar suppresses lower-priority ones until it
 * expires. Every other region is last-write-wins.
 */
public object HudChannelPolicy {

    /** Tick at which [layout] stops being visible; `Long.MAX_VALUE` for layouts without a TTL. */
    public fun expiresAt(layout: HudLayout, nowTick: Long): Long =
        if (layout is HudLayout.ActionBar) nowTick + layout.ttlTicks else Long.MAX_VALUE

    /** Whether the live [existing] layout (visible until [existingExpiresAt]) blocks [incoming]. */
    public fun suppresses(
        existing: HudLayout,
        existingExpiresAt: Long,
        incoming: HudLayout,
        nowTick: Long,
    ): Boolean =
        existing is HudLayout.ActionBar &&
            incoming is HudLayout.ActionBar &&
            existingExpiresAt > nowTick &&
            existing.priority > incoming.priority
}

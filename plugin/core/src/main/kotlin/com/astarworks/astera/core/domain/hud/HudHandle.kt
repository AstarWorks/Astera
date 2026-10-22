package com.astarworks.astera.core.domain.hud

/**
 * Opaque handle returned by [com.astarworks.astera.core.application.port.outbound.IHudGateway.showFor]
 * (Wave 5j, ADR-0097).
 *
 * Used by callers to later `updateFor` / `clearFor` a specific HUD render
 * without affecting other HUD elements on the same player. Stable for the
 * gateway impl's lifetime; not persistent across plugin restarts (HUD is
 * ephemeral by design).
 */
@JvmInline
public value class HudHandle(public val value: String) {
    init { require(value.isNotBlank()) { "HudHandle must not be blank" } }

    override fun toString(): String = value
}

/**
 * Which screen region the HUD targets. Used by `IHudGateway.clearFor` to
 * clear only one region without affecting others (e.g. clear sidebar but
 * leave action bar alone).
 */
public enum class HudRegion {
    ACTION_BAR,
    BOSS_BAR_SLOT_1,
    BOSS_BAR_SLOT_2,
    BOSS_BAR_SLOT_3,
    BOSS_BAR_SLOT_4,
    SIDEBAR,
    TAB_LIST_HEADER,
    TAB_LIST_FOOTER,
    SUBTITLE,

    /** The always-on status line (HP bar), composited into the action bar by the gateway (ADR-0182). */
    STATUS_LINE,
}

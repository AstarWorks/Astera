package com.astarworks.astera.core.domain.hud

/** How a floating damage number is drawn (ADR-0206). */
public enum class DamageIndicatorStyle {
    /** An ordinary hit. */
    NORMAL,

    /** A critical hit: bigger, gold. */
    CRITICAL,

    /** Fully absorbed (blocked): grey. */
    BLOCKED,
}

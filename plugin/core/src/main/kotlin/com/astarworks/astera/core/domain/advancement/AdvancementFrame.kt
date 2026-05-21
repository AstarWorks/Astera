package com.astarworks.astera.core.domain.advancement

/**
 * Visual presentation tier for a [CustomAdvancement] (Wave 5p, ADR-0088).
 *
 * Mirrors Vanilla's `frame` field (Task / Goal / Challenge) but evaluated by
 * Astera's own HUD adapter — no Bukkit advancement API is used at runtime.
 *
 *  - [TASK]: standard milestone.
 *  - [GOAL]: medium tier (gold frame in Vanilla; Astera renders with a
 *    distinct accent in its UI).
 *  - [CHALLENGE]: top tier; typically requires several precursor advancements
 *    via [CustomAdvancement.parent] and grants higher-value rewards.
 */
public enum class AdvancementFrame {
    TASK,
    GOAL,
    CHALLENGE,
}

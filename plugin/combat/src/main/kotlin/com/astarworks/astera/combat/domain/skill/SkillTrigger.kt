package com.astarworks.astera.combat.domain.skill

/**
 * Input action that fires a skill. Modeled as a vendor-neutral enum so the
 * domain never references Bukkit's `Action` or `PlayerInteractEvent` shapes.
 *
 * Wave 5a established the original 6 values; Wave 5a-fix2 saturated the
 * vocabulary to 20 values matching RTM `model/skill/SkillTrigger.kt`
 * (ADR-0029 §"Deep audit (2026-05-19)"). The new 14 values are pure
 * vocabulary — Bukkit handlers wiring them to runtime input events land in
 * Wave 6.
 *
 * `HOLD` is a sustained press (channel-style skills); `AUTO` is invoked by
 * scheduled triggers rather than player input. `SYSTEM` is reserved for
 * server-side fires (e.g., anomaly / mode listeners pushing a skill cast).
 */
public enum class SkillTrigger {
    // Wave 5a baseline
    LEFT_CLICK,
    RIGHT_CLICK,
    SNEAK_LEFT,
    SNEAK_RIGHT,
    HOLD,
    AUTO,

    // Wave 5a-fix2 saturation (RTM SkillTrigger.kt parity — 14 additions)
    LEFT_CLICK_BLOCK,
    RIGHT_CLICK_BLOCK,
    LEFT_CLICK_AIR,
    RIGHT_CLICK_AIR,
    INTERACT,
    SHOOT,
    BLOCK_BREAK,
    CONSUME,
    CROUCH,
    JUMP,
    SPRINT,
    DROP,
    STACK_DROP,
    PICKUP,
    HYPER,
    ATTACK,
    SHIELD,
    DAMAGED,
    DEATH,
    SYSTEM,
}

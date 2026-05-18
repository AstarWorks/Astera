package com.astarworks.astera.match.domain.machine

/**
 * Sealed enumeration of [SkillCoreMachine] firing conditions (Wave 5d,
 * P2 minimal).
 *
 * Phase 1 PoC ships [RedstoneActivated] only: the trigger that mirrors
 * vanilla redstone wiring most directly and exercises the
 * `SkillCoreMachine` end-to-end without requiring new player-input or
 * timer-tick plumbing. Wave 6+ will add e.g. `EntityProximity`,
 * `OnDamage`, `ScheduledInterval`.
 *
 * Each variant is a `data object` so equality / `when` exhaustiveness
 * behave naturally and the compiler flags every consumer that hasn't
 * handled a newly-added variant.
 */
public sealed class TriggerCondition {

    /**
     * Fire when the [SkillCoreMachine]'s tile receives a redstone power
     * pulse (rising edge). Cooldown semantics live on the skill itself
     * via the combat plugin's cooldown machinery.
     */
    public data object RedstoneActivated : TriggerCondition()
}

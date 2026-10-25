package com.astarworks.astera.core.domain.mob

import com.astarworks.astera.core.domain.skill.SkillId

/**
 * One trigger → skill mapping on a [MobSpec] (Wave 5pve, ADR-0085).
 *
 * Mirrors the philosophy of [com.astarworks.astera.core.domain.item.ItemSkillBinding]
 * (item-side, ADR-0073) but with mob-specific triggers — items react to
 * player input; mobs react to combat state + spatial events. The skill side
 * is **the same Astera SkillId vocabulary** so combat resolution stays
 * single-sourced: a wolf bite and a player sword swing both route through
 * `SkillCastRequested` and `ISkillRegistry`.
 *
 * # Trigger semantics
 *
 * Adapter-side trigger detection lives in the Paper wiring (Wave 6b) — the
 * pure-domain shape here just enumerates the families. The Minimal adapter
 * routes triggers through `MobSkillRouter` (Wave 6b) which publishes
 * `SkillCastRequested` so combat resolves the skill identically to a
 * player-fired binding.
 *
 * # Why a sealed interface, not an enum
 *
 * Each variant carries its own parameters (cooldown, threshold, range,
 * interval). An enum + companion config map would force callers to look up
 * params separately; sealed `data` records keep the binding self-contained.
 */
public sealed interface MobSkillBinding {

    /** The skill to cast when this binding fires. */
    public val skillId: SkillId

    /**
     * Fires when the mob takes damage. [triggerChance] in `[0, 1]` controls
     * probabilistic activation (e.g. 0.25 = 25% of incoming hits).
     */
    public data class OnDamaged(
        override val skillId: SkillId,
        val triggerChance: Float = 1f,
    ) : MobSkillBinding {
        init {
            require(triggerChance in 0f..1f) {
                "OnDamaged.triggerChance must be in [0, 1]: $triggerChance"
            }
        }
    }

    /**
     * Fires when a player enters [range] (block-distance). [cooldownTicks]
     * gates re-firing so a stationary player doesn't trigger every tick.
     */
    public data class OnPlayerNear(
        override val skillId: SkillId,
        val range: Double,
        val cooldownTicks: Int,
    ) : MobSkillBinding {
        init {
            require(range > 0.0) { "OnPlayerNear.range must be positive: $range" }
            require(cooldownTicks >= 0) {
                "OnPlayerNear.cooldownTicks must be non-negative: $cooldownTicks"
            }
        }
    }

    /**
     * Fires when the mob's HP drops below [percent] of max. [percent] in
     * `[0, 1]`. Fires **once per spawn** (the adapter tracks whether it has
     * fired) — re-spawned mobs re-arm the threshold.
     */
    public data class OnHpThreshold(
        override val skillId: SkillId,
        val percent: Float,
    ) : MobSkillBinding {
        init {
            require(percent in 0f..1f) {
                "OnHpThreshold.percent must be in [0, 1]: $percent"
            }
        }
    }

    /**
     * Fires once when the mob dies, before the loot table resolves. Useful
     * for death-rattle AoE, explode-on-death, summon-replacement patterns.
     */
    public data class OnDeath(
        override val skillId: SkillId,
    ) : MobSkillBinding

    /**
     * Fires every [intervalTicks] while the mob is alive. The adapter is
     * responsible for the scheduler ticking; the binding only carries the
     * cadence.
     */
    public data class Periodic(
        override val skillId: SkillId,
        val intervalTicks: Int,
    ) : MobSkillBinding {
        init {
            require(intervalTicks > 0) {
                "Periodic.intervalTicks must be positive: $intervalTicks"
            }
        }
    }
}

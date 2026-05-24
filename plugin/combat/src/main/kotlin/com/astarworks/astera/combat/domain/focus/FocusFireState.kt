package com.astarworks.astera.combat.domain.focus

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Live focus-fire state on a victim (Wave 5combat-depth, ADR-0105).
 *
 * Owned + mutated by `IFocusFireTracker`; consumers read snapshots.
 *
 * # Activation rule
 *
 * [isActive] (computed against an external [FocusFireConfig]) is true
 * when at least [FocusFireConfig.minAttackers] of [contributions] have
 * a `lastHitAtTick` within the active window. The tracker recomputes
 * activation on every recorded hit and on each scheduled prune.
 *
 * @property victim Player taking the focused damage.
 * @property contributions One entry per attacker that has hit the
 *   victim within the longest possible active window. Stale entries
 *   (older than the window) are pruned by the tracker; they never
 *   live forever.
 * @property windowStartedAtTick Server tick when the first hit of the
 *   current focus window landed. Used as the decay anchor — once
 *   `nowTick - windowStartedAtTick > windowTicks` with no new hits,
 *   the focus collapses.
 */
public data class FocusFireState(
    public val victim: PlayerId,
    public val contributions: List<FocusFireContribution>,
    public val windowStartedAtTick: Long,
) {
    init {
        require(contributions.isNotEmpty()) {
            "FocusFireState($victim): contributions must not be empty — drop the state instead."
        }
        require(windowStartedAtTick >= 0L) {
            "FocusFireState.windowStartedAtTick must be >= 0: $windowStartedAtTick"
        }
        val attackers = contributions.map { it.attacker }
        require(attackers.size == attackers.toSet().size) {
            "FocusFireState.contributions must have one entry per attacker, got duplicates: $attackers"
        }
        require(victim !in attackers) {
            "FocusFireState.contributions must not include the victim as an attacker: $victim"
        }
    }

    /** Total damage from every contribution. */
    public val totalDamage: Double get() = contributions.sumOf { it.totalDamage }

    /** Distinct attackers currently contributing. */
    public val attackerCount: Int get() = contributions.size

    /**
     * True iff focus-fire is active under [config] at [nowTick] — at least
     * [FocusFireConfig.minAttackers] attackers have hit within
     * `[nowTick - windowTicks, nowTick]`.
     */
    public fun isActive(config: FocusFireConfig, nowTick: Long): Boolean {
        val cutoff = nowTick - config.windowTicks
        val recent = contributions.count { it.lastHitAtTick >= cutoff }
        return recent >= config.minAttackers
    }
}

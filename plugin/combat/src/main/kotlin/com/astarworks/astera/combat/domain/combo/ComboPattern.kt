package com.astarworks.astera.combat.domain.combo

import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Declarative match condition for a [ComboRoute] (Wave 5combat-depth, ADR-0105).
 *
 * The route fires when its pattern matches the player's current [ComboState].
 * Patterns are sealed: each variant matches a different kind of progression
 * (raw hit count for fighter-style chains, skill sequences for spell
 * rotations, timing-window for "true combo" inputs).
 *
 * # Why sealed, not a single "any of N" predicate
 *
 * Astera's combo philosophy distinguishes "press X 5 times" (HitCount) from
 * "spell A → spell B → spell C in that order" (SkillSequence) from "land 3
 * hits inside a 10-tick window" (TimingWindow). Folding them into one
 * predicate would obscure intent for both YAML authors and the matcher
 * implementation. Per Phase 1 PoC posture, adding a new variant later is a
 * supersede-or-extend decision, not a hidden expansion of an existing one.
 *
 * # Evaluation contract
 *
 * Matching is **prefix-or-equal**: the route fires the first tick its
 * pattern is satisfied by the recorded steps. The tracker emits
 * `ComboRouteCompleted` exactly once per route per state lifetime. Repeated
 * triggering of the same route requires the combo to expire and restart.
 */
public sealed interface ComboPattern {

    /**
     * Match when the combo's [ComboState.streakCount] reaches [target].
     *
     * Hit type is intentionally polymorphic — Hit + SkillCast both count.
     * If you need "5 hits, no skill casts", combine with a future
     * `ComboPattern.PureHit` variant (deferred).
     */
    public data class HitCount(public val target: Int) : ComboPattern {
        init {
            require(target > 0) { "ComboPattern.HitCount.target must be > 0: $target" }
        }
    }

    /**
     * Match when the combo's recorded [ComboStep.SkillCast] sequence
     * **ends with** [skills] in exact order. Allows preamble steps so a
     * player can spam autoattacks before casting the rotation, but the
     * tail must be exact.
     *
     * Empty [skills] is rejected (would match every combo trivially).
     */
    public data class SkillSequence(public val skills: List<SkillId>) : ComboPattern {
        init {
            require(skills.isNotEmpty()) {
                "ComboPattern.SkillSequence.skills must not be empty"
            }
        }
    }

    /**
     * Match when the combo accumulated [hits] within [windowTicks] of its
     * first counting step. Models "true combo" / "perfect rhythm" chains
     * (fighter-style press timing).
     */
    public data class TimingWindow(
        public val hits: Int,
        public val windowTicks: Int,
    ) : ComboPattern {
        init {
            require(hits > 1) { "ComboPattern.TimingWindow.hits must be > 1: $hits" }
            require(windowTicks > 0) {
                "ComboPattern.TimingWindow.windowTicks must be > 0: $windowTicks"
            }
        }
    }
}

package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.skill.SkillId

/**
 * One phase of a multi-phase boss fight (Wave 5pve-boss, ADR-0098).
 *
 * Phases are the unit of boss-mechanic evolution: when a transition fires,
 * the boss switches phase, optionally entering an immunity window, optionally
 * casting a single "transition skill", and exposing a new set of
 * [periodicSkills] / [onHitSkills] until the next transition.
 *
 * # Why a value record, not a state machine
 *
 * The state machine lives in `IBossLifecycleService` (Wave 6b orchestrates
 * the actual transitions when HP crosses thresholds). [BossPhase] is the
 * pure description — index + display name + skill bindings + optional
 * one-shot transition cast. This keeps the domain side trivial to test and
 * lets the adapter side decide how to wire timers / hp listeners.
 *
 * Transition skill semantics: [transitionCast] fires exactly once when the
 * boss enters this phase (after [com.astarworks.astera.core.domain.event.BossPhaseChanged]
 * is published). It is null for the initial phase since "entering phase 0"
 * happens at spawn time and the spawn animation is the cue.
 *
 * @property index 0-based phase ordinal. The initial phase MUST have
 *   `index = 0`; subsequent phases MUST have strictly increasing indices
 *   per [BossSpec.phases] ordering (enforced in [BossSpec]).
 * @property nameKey i18n key for the phase nameplate (e.g.
 *   `boss.scorched_titan.phase.enraged`). Adapter renders via `LocalizedText`.
 * @property immunityTicks Number of server ticks the boss is immune after
 *   entering this phase. 0 = no immunity window.
 * @property transitionCast Optional skill cast on phase entry (null for
 *   index 0). Resolved through Astera `ISkillRegistry` like any other skill.
 * @property periodicSkills Skills the boss casts on a recurring timer while
 *   in this phase. The lifecycle service decides cadence (per-skill cooldowns
 *   live in `SkillSpec`, not here).
 * @property onHitSkills Skills the boss may cast in response to taking
 *   damage while in this phase (the lifecycle service applies its own throttle
 *   so a flurry of damage doesn't trigger every-skill chains).
 */
public data class BossPhase(
    public val index: Int,
    public val nameKey: String,
    public val immunityTicks: Int = 0,
    public val transitionCast: SkillId? = null,
    public val periodicSkills: List<SkillId> = emptyList(),
    public val onHitSkills: List<SkillId> = emptyList(),
) {
    init {
        require(index >= 0) { "BossPhase.index must be >= 0: $index" }
        require(nameKey.isNotBlank()) { "BossPhase.nameKey must not be blank" }
        require(immunityTicks >= 0) {
            "BossPhase.immunityTicks must be >= 0: $immunityTicks"
        }
        if (index == 0) {
            require(transitionCast == null) {
                "BossPhase index 0 (initial phase) must not declare a transitionCast — " +
                    "the initial phase is entered at spawn, not via transition."
            }
        }
    }
}

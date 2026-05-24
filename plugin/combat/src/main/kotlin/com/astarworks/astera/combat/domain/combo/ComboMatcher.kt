package com.astarworks.astera.combat.domain.combo

/**
 * Pure matcher: given a [ComboState] and a [ComboRoute], decide whether
 * the route fires (Wave 5combat-depth, ADR-0105).
 *
 * Lives in `domain/` because matching is a deterministic function of state
 * — no I/O, no clocks, no registry lookups. The lifecycle service in
 * `application/` composes the matcher with the registry to fire effects.
 */
public object ComboMatcher {

    /** Returns true iff [route]'s pattern matches [state]. */
    public fun matches(state: ComboState, route: ComboRoute): Boolean {
        if (state.weaponId != route.weaponId) return false
        return when (val p = route.pattern) {
            is ComboPattern.HitCount -> state.streakCount >= p.target

            is ComboPattern.SkillSequence -> {
                val casts = state.steps.filterIsInstance<ComboStep.SkillCast>()
                if (casts.size < p.skills.size) return false
                val tail = casts.takeLast(p.skills.size).map { it.skill }
                tail == p.skills
            }

            is ComboPattern.TimingWindow -> {
                if (state.streakCount < p.hits) return false
                val window = state.steps.takeLast(p.hits)
                val span = window.last().atTick - window.first().atTick
                span <= p.windowTicks
            }
        }
    }
}

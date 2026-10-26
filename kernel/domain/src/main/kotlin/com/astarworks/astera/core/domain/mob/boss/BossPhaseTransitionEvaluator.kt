package com.astarworks.astera.core.domain.mob.boss

/**
 * Pure evaluator: given a [BossPhaseTransition] and a
 * [BossPhaseEvaluationContext], decide whether the trigger fires
 * (Wave 5combat-depth, ADR-0105).
 *
 * Lives in `core/domain/` because the decision is a deterministic function
 * of trigger + context — no I/O, no clocks beyond the context tick, no
 * registry lookups. The lifecycle service composes the evaluator with the
 * `BossSpec.transitions` list to drive phase changes.
 *
 * # Phase 1 vs. forward-looking
 *
 * Phase 1's `IBossLifecycleService` currently inlines the equivalent of
 * `HpThreshold` evaluation inside `onDamage`. Wave 5combat-depth ships
 * this evaluator as the **forward-looking single point of truth** so the
 * follow-up wave that teaches the lifecycle to evaluate the four new
 * trigger kinds doesn't have to invent the matching algorithm. Until then,
 * the evaluator is wired by `AsteraCorePlugin` as a [BossPhaseTransitionEvaluator]
 * service entry so YAML-authored bosses with the new triggers can be
 * registered (the lifecycle service skips them silently in Phase 1; Wave
 * 6b will switch to calling the evaluator).
 */
public object BossPhaseTransitionEvaluator {

    /**
     * Returns true iff [trigger] fires under [context].
     *
     * Pre-condition: the caller has already checked
     * `trigger.fromPhase == context.currentPhase` (the lifecycle service is
     * the natural place for that filter — including it here would double-
     * evaluate inside [BossPhaseTransition.AllOf]).
     */
    public fun fires(
        trigger: BossPhaseTransition,
        context: BossPhaseEvaluationContext,
    ): Boolean {
        return when (trigger) {
            is BossPhaseTransition.HpThreshold ->
                context.hpFraction <= trigger.hpFraction

            is BossPhaseTransition.TimerExpired ->
                context.ticksInCurrentPhase >= trigger.elapsedTicks

            is BossPhaseTransition.MultiPlayerThreshold ->
                context.attackerCount >= trigger.minPlayers

            is BossPhaseTransition.MechanicCompleted ->
                trigger.mechanicSkillId in context.completedMechanics

            is BossPhaseTransition.AllOf ->
                trigger.children.all { child -> fires(child, context) }
        }
    }
}

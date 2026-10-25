package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Read-only context the lifecycle service feeds to
 * [BossPhaseTransitionEvaluator] on each evaluation tick (Wave 5combat-depth,
 * ADR-0105).
 *
 * The Phase 1 [com.astarworks.astera.core.application.port.outbound.mob.IBossLifecycleService]
 * keeps this context inside its in-memory state (HP fraction is already
 * tracked; the new fields are additive). Adapter-side observers (Wave 6b
 * Paper) populate the context from the live boss instance.
 *
 * # Why a value record, not a service-call back into the lifecycle
 *
 * The evaluator is **pure**: given the current trigger + this context, it
 * decides match-or-no-match without side effects. That makes it trivially
 * testable (`BossPhaseTransitionEvaluatorTest`) and keeps the lifecycle
 * service free to assemble the context however it wants (in-memory in
 * Phase 1, Paper-backed in Wave 6, Postgres-snapshot during replay).
 *
 * @property currentPhase Phase the boss is currently in. Trigger.fromPhase
 *   must match this for the trigger to be eligible.
 * @property hpFraction Current `(currentHp / maxHp)`. Drives [BossPhaseTransition.HpThreshold].
 * @property nowTick Server tick the evaluation runs at. Drives
 *   [BossPhaseTransition.TimerExpired] together with [phaseEnteredAtTick].
 * @property phaseEnteredAtTick Server tick at which [currentPhase] was
 *   entered. The lifecycle service updates this on every phase change.
 * @property attackerCount Number of distinct players who have damaged the
 *   boss since the encounter began. Drives
 *   [BossPhaseTransition.MultiPlayerThreshold].
 * @property completedMechanics Set of mechanic-skill ids the raid has
 *   completed during this encounter. Drives
 *   [BossPhaseTransition.MechanicCompleted].
 */
public data class BossPhaseEvaluationContext(
    public val currentPhase: Int,
    public val hpFraction: Double,
    public val nowTick: Long,
    public val phaseEnteredAtTick: Long,
    public val attackerCount: Int,
    public val completedMechanics: Set<SkillId> = emptySet(),
) {
    init {
        require(currentPhase >= 0) {
            "BossPhaseEvaluationContext.currentPhase must be >= 0: $currentPhase"
        }
        require(hpFraction.isFinite() && hpFraction in 0.0..1.0) {
            "BossPhaseEvaluationContext.hpFraction must be finite and in [0.0, 1.0]: $hpFraction"
        }
        require(nowTick >= 0L) {
            "BossPhaseEvaluationContext.nowTick must be >= 0: $nowTick"
        }
        require(phaseEnteredAtTick >= 0L) {
            "BossPhaseEvaluationContext.phaseEnteredAtTick must be >= 0: $phaseEnteredAtTick"
        }
        require(phaseEnteredAtTick <= nowTick) {
            "BossPhaseEvaluationContext.phaseEnteredAtTick ($phaseEnteredAtTick) must be <= nowTick ($nowTick)"
        }
        require(attackerCount >= 0) {
            "BossPhaseEvaluationContext.attackerCount must be >= 0: $attackerCount"
        }
    }

    /** Ticks elapsed since the boss entered [currentPhase]. */
    public val ticksInCurrentPhase: Long get() = nowTick - phaseEnteredAtTick
}

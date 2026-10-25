package com.astarworks.astera.core.domain.mob.boss

import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Trigger that promotes a boss from one [BossPhase] to the next
 * (Wave 5pve-boss, ADR-0098; expanded Wave 5combat-depth, ADR-0105).
 *
 * # History
 *
 * Wave 5pve-boss shipped only [HpThreshold]. Wave 5combat-depth widens the
 * sealed surface with four additional variants ([TimerExpired],
 * [MultiPlayerThreshold], [MechanicCompleted], [AllOf]) so the Boss DSL can
 * express the multi-mechanic encounters that the Annihilation PoC needs
 * (Scorched Titan enrage on player count OR time elapsed; Abyssal Lich phase
 * gate behind a mechanic completion).
 *
 * # What still belongs to the lifecycle service
 *
 * The sealed types declare **what** a trigger fires on. **When** they fire is
 * the responsibility of `IBossLifecycleService` — the Phase 1 implementation
 * only evaluates [HpThreshold] inside `onDamage`. The new variants are
 * vocabulary + a pure [BossPhaseTransitionEvaluator] so authors can write
 * YAML now; the lifecycle service learns to evaluate them in a follow-up
 * wave (no migration is required because the existing constructor signature
 * is unchanged — adding a sealed subtype is source-additive).
 *
 * # Evaluation contract (unchanged)
 *
 * The lifecycle service evaluates transitions in the order they appear in
 * [BossSpec.transitions]. The first matching transition fires and the boss
 * moves to [toPhase]. Transitions never roll back — once a phase is entered,
 * the next transition's `fromPhase` must be > the current phase, otherwise
 * the spec is rejected at registration time.
 */
public sealed interface BossPhaseTransition {

    /** Phase the boss must currently be in for this transition to be eligible. */
    public val fromPhase: Int

    /** Phase the boss enters when this transition fires. Must be > [fromPhase]. */
    public val toPhase: Int

    /**
     * HP-percentage-driven transition. Fires the first server tick on which
     * `(currentHp / maxHp) <= hpFraction` while the boss is in [fromPhase].
     *
     * Phase 1 PoC convention: thresholds are listed in descending order
     * (`0.66 → 0.33`) so the natural ordering of [BossSpec.transitions] is
     * also the firing order as the boss takes damage.
     */
    public data class HpThreshold(
        public override val fromPhase: Int,
        public override val toPhase: Int,
        public val hpFraction: Double,
    ) : BossPhaseTransition {
        init {
            require(fromPhase >= 0) { "HpThreshold.fromPhase must be >= 0: $fromPhase" }
            require(toPhase > fromPhase) {
                "HpThreshold: toPhase ($toPhase) must be > fromPhase ($fromPhase)"
            }
            require(hpFraction in 0.0..1.0) {
                "HpThreshold.hpFraction must be in [0.0, 1.0]: $hpFraction"
            }
        }
    }

    /**
     * Time-driven transition (Wave 5combat-depth, ADR-0105).
     *
     * Fires the first server tick on which `(nowTick - phaseEnteredAtTick) >=
     * elapsedTicks` while the boss is in [fromPhase]. Use for soft enrage
     * timers ("if the raid hasn't burned the boss in 90 s, escalate").
     *
     * Phase entry tick is bookkept by the lifecycle service; the evaluator
     * receives it through [BossPhaseEvaluationContext].
     */
    public data class TimerExpired(
        public override val fromPhase: Int,
        public override val toPhase: Int,
        public val elapsedTicks: Long,
    ) : BossPhaseTransition {
        init {
            require(fromPhase >= 0) { "TimerExpired.fromPhase must be >= 0: $fromPhase" }
            require(toPhase > fromPhase) {
                "TimerExpired: toPhase ($toPhase) must be > fromPhase ($fromPhase)"
            }
            require(elapsedTicks > 0L) {
                "TimerExpired.elapsedTicks must be > 0: $elapsedTicks"
            }
        }
    }

    /**
     * Player-count-driven transition (Wave 5combat-depth, ADR-0105).
     *
     * Fires when the count of distinct attacking players within the boss's
     * threat list reaches [minPlayers] while in [fromPhase]. Models "enrage
     * when at least 5 players engage" mechanics.
     *
     * Threat-list semantics are the lifecycle service's responsibility
     * (typically "any player who has dealt damage to the boss"). The
     * evaluator only needs the count, supplied through
     * [BossPhaseEvaluationContext.attackerCount].
     */
    public data class MultiPlayerThreshold(
        public override val fromPhase: Int,
        public override val toPhase: Int,
        public val minPlayers: Int,
    ) : BossPhaseTransition {
        init {
            require(fromPhase >= 0) {
                "MultiPlayerThreshold.fromPhase must be >= 0: $fromPhase"
            }
            require(toPhase > fromPhase) {
                "MultiPlayerThreshold: toPhase ($toPhase) must be > fromPhase ($fromPhase)"
            }
            require(minPlayers >= 1) {
                "MultiPlayerThreshold.minPlayers must be >= 1: $minPlayers"
            }
        }
    }

    /**
     * Mechanic-driven transition (Wave 5combat-depth, ADR-0105).
     *
     * Fires when the raid completes the mechanic identified by [mechanicSkillId]
     * (typically a player-cast skill that the boss expects, like "interrupt the
     * channel" or "destroy the totem"). Models scripted encounters where the
     * raid must DO something specific to advance the fight, rather than just
     * dealing damage.
     *
     * The lifecycle service records "completed mechanic ids per phase" and
     * supplies the set through [BossPhaseEvaluationContext.completedMechanics].
     * We reuse [SkillId] for the mechanic key because skills are already the
     * vocabulary of "player-initiated actions in Astera"; introducing a
     * separate `MechanicId` would just duplicate the registry.
     */
    public data class MechanicCompleted(
        public override val fromPhase: Int,
        public override val toPhase: Int,
        public val mechanicSkillId: SkillId,
    ) : BossPhaseTransition {
        init {
            require(fromPhase >= 0) {
                "MechanicCompleted.fromPhase must be >= 0: $fromPhase"
            }
            require(toPhase > fromPhase) {
                "MechanicCompleted: toPhase ($toPhase) must be > fromPhase ($fromPhase)"
            }
        }
    }

    /**
     * Conjunction trigger (Wave 5combat-depth, ADR-0105).
     *
     * Fires when **every** child trigger fires for the same `(fromPhase,
     * toPhase)`. Used for "20% HP AND 90 s elapsed" enrage gates. Every
     * child MUST share this trigger's [fromPhase] / [toPhase]; the
     * constructor enforces it so YAML mistakes surface immediately.
     *
     * Disjunction is **not** provided — overlapping HP+time triggers can be
     * modelled by two separate top-level entries on the same `(from, to)`
     * pair (the lifecycle service short-circuits on the first match). This
     * keeps the sealed surface small and the YAML readable.
     */
    public data class AllOf(
        public override val fromPhase: Int,
        public override val toPhase: Int,
        public val children: List<BossPhaseTransition>,
    ) : BossPhaseTransition {
        init {
            require(fromPhase >= 0) { "AllOf.fromPhase must be >= 0: $fromPhase" }
            require(toPhase > fromPhase) {
                "AllOf: toPhase ($toPhase) must be > fromPhase ($fromPhase)"
            }
            require(children.size >= 2) {
                "AllOf.children must have >= 2 entries (single-child AllOf collapses to the child): ${children.size}"
            }
            children.forEach { child ->
                require(child.fromPhase == fromPhase && child.toPhase == toPhase) {
                    "AllOf: every child must share fromPhase=$fromPhase toPhase=$toPhase, got " +
                        "fromPhase=${child.fromPhase} toPhase=${child.toPhase}"
                }
                require(child !is AllOf) {
                    "AllOf: nested AllOf is not allowed — flatten the children list"
                }
            }
        }
    }
}

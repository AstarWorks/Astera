package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.damage.DamageAttempt

/**
 * Outbound port: pluggable damage-bonus contributor consulted by
 * [com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase]
 * after [com.astarworks.astera.combat.domain.rule.DamageRule.resolve] returns
 * (Wave K 5integration-wire, ADR-0128).
 *
 * # Why a port (and not "stuff every multiplier into [DamageRule] modifiers")
 *
 * The modifiers in `DamageRule` are *pure* (no I/O, no time, no per-player
 * state) — they participate in [com.astarworks.astera.combat.domain.rule.WorkingDamage]
 * mutation and are called in deterministic order. Combo / focus-fire /
 * target-lock bonuses are different in kind:
 *
 *  - they consult **time-windowed runtime state** (the three Wave 5combat-depth
 *    trackers),
 *  - they may **consume** state on read (combo bonus is one-shot, target-lock
 *    bonus is one-shot per acquisition),
 *  - they are **optional** wires that adapter layers (or feature plugins) add
 *    via the ServiceRegistry — `:plugin:combat` core ships without any default
 *    bonus source, so tests that only care about base damage do not need to
 *    pre-stub them away.
 *
 * Keeping them outside the rule means `DamageRule.resolve` stays a pure
 * function over (attempt, armor, container, team) and the Wave K wires can
 * land + be unwired independently.
 *
 * # Contract
 *
 *  - Return `1.0` when this source has no opinion about the current attempt.
 *  - Return `> 1.0` to amplify (or `< 1.0` to dampen — Wave K only uses
 *    amplification but the surface stays symmetric).
 *  - **Implementations may mutate internal state** (the combo source clears a
 *    pending bonus; the target-lock controller's bonus is single-use). The
 *    use case calls each source exactly once per [bonusFor] invocation, and
 *    only after the friendly-fire short-circuit has cleared.
 *  - Return value MUST be finite and `>= 0.0`. The use case enforces this
 *    via [require] before applying.
 *
 * # Multiplication order
 *
 * The use case multiplies sources in **registration order** (left to right):
 * `final = ruleResolved * combo * focusFire * targetLock`. Order doesn't
 * affect the magnitude (multiplication is commutative for finite reals) but
 * it determines who "wins" the consume race when two sources happen to be
 * armed simultaneously — combo is registered first so it consumes first.
 */
public fun interface IDamageBonusSource {

    /**
     * Compute the multiplier this source contributes to [attempt] at
     * [nowTick]. Return `1.0` for "no contribution".
     */
    public fun bonusFor(attempt: DamageAttempt, nowTick: Long): Double
}

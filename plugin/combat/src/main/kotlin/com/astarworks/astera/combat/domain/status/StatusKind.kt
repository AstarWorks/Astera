package com.astarworks.astera.combat.domain.status

/**
 * Categorization of status effects.
 *
 * Mirrors the RTM status taxonomy: star-gain modifiers, heal modifiers,
 * gravity/CT/speed shifts, plus a handful of action-disabling states.
 * `magnitude` on [StatusEffectSpec] interprets per-kind (e.g. multiplier for
 * `SPEED_UP`, absolute amount for `STAR_GAIN`).
 */
public enum class StatusKind(
    /** Debuff: removed by `Cleanse(onlyHarmful = true)`. */
    public val harmful: Boolean,
) {
    STAR_GAIN(false),
    STAR_GAIN_BOOST(false),
    STAR_GAIN_NULL(true),
    HEAL_AMP(false),
    HEAL_REDUCE(true),
    HEAL_NULL(true),
    GRAVITY_UP(true),
    GRAVITY_DOWN(false),
    CT_RATE_UP(false),
    CT_RATE_DOWN(true),
    CT_FREEZE(true),
    SPEED_UP(false),
    SPEED_DOWN(true),
    PARALYSIS(true),
    BURN(true),
    JUMP_DISABLED(true),

    /** Cannot cast skills (`CastSkillUseCase` checks the `silence` status id). */
    SILENCE(true),

    /**
     * Pure stack counter with no engine-side behaviour (Tremor / Rupture / ...). Skills read the
     * stack count through `has_status.min_stacks` and `damage.per_stack`. Removed by a harmful Cleanse.
     */
    STACK_DEBUFF(true),

    /** Pure stack counter on the user's side (Poise / Charge / ...); survives a harmful Cleanse. */
    STACK_BUFF(false),
}

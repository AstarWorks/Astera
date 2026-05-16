package com.astarworks.astera.domain.model.status

/**
 * Categorization of status effects.
 *
 * Mirrors the RTM status taxonomy: star-gain modifiers, heal modifiers,
 * gravity/CT/speed shifts, plus a handful of action-disabling states.
 * `magnitude` on [StatusEffectSpec] interprets per-kind (e.g. multiplier for
 * `SPEED_UP`, absolute amount for `STAR_GAIN`).
 */
public enum class StatusKind {
    STAR_GAIN,
    STAR_GAIN_BOOST,
    STAR_GAIN_NULL,
    HEAL_AMP,
    HEAL_REDUCE,
    HEAL_NULL,
    GRAVITY_UP,
    GRAVITY_DOWN,
    CT_RATE_UP,
    CT_RATE_DOWN,
    CT_FREEZE,
    SPEED_UP,
    SPEED_DOWN,
    PARALYSIS,
    BURN,
    JUMP_DISABLED,
}

package com.astarworks.astera.domain.model.status

import com.astarworks.astera.domain.model.i18n.MessageKey

/**
 * Immutable status-effect definition.
 *
 * The YAML in `content/effects/` is deserialized (in the application layer)
 * into [StatusEffectSpec] and registered with an effect registry. The spec
 * carries no runtime state; applied instances live in [StatusEffectInstance].
 *
 * `magnitude` semantics vary by [StatusKind]: multiplier (e.g. `SPEED_UP`),
 * absolute amount (`STAR_GAIN`), or ignored for action-disabling kinds
 * (`PARALYSIS`, `CT_FREEZE`, `JUMP_DISABLED`).
 */
public data class StatusEffectSpec(
    val id: StatusEffectId,
    val displayNameKey: MessageKey,
    val kind: StatusKind,
    val magnitude: Double,
    val stackable: Boolean,
    val maxStacks: Int,
) {
    init {
        require(maxStacks >= 1) { "maxStacks must be >= 1: $maxStacks" }
    }
}

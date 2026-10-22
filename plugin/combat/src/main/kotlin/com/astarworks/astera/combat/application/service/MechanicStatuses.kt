package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * Statuses the Freeze / Silence mechanics apply. [SILENCE_ID] is the id
 * `CastSkillUseCase` consults to refuse casts, so Silence needs no change there.
 * A `content/status/` entry with the same id wins over the built-in spec.
 */
internal object MechanicStatuses {
    val FREEZE_ID: StatusEffectId = StatusEffectId("freeze")
    val SILENCE_ID: StatusEffectId = StatusEffectId("silence")

    /** The id `ApplyDamageUseCase` treats as full damage immunity; DashStrike's `invulnerable` rides it. */
    val IMMUNITY_ID: StatusEffectId = StatusEffectId("immunity")

    val IMMUNITY: StatusEffectSpec = StatusEffectSpec(
        id = IMMUNITY_ID,
        displayNameKey = MessageKey("astera.status.immunity.name"),
        kind = StatusKind.STACK_BUFF,
        magnitude = 0.0,
        stackable = false,
        maxStacks = 1,
    )

    val FREEZE: StatusEffectSpec = StatusEffectSpec(
        id = FREEZE_ID,
        displayNameKey = MessageKey("astera.status.freeze.name"),
        kind = StatusKind.PARALYSIS,
        magnitude = 0.0,
        stackable = false,
        maxStacks = 1,
    )

    val SILENCE: StatusEffectSpec = StatusEffectSpec(
        id = SILENCE_ID,
        displayNameKey = MessageKey("astera.status.silence.name"),
        kind = StatusKind.SILENCE,
        magnitude = 0.0,
        stackable = false,
        maxStacks = 1,
    )
}

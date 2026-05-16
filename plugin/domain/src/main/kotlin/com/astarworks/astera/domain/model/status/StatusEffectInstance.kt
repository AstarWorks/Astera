package com.astarworks.astera.domain.model.status

import com.astarworks.astera.domain.model.player.PlayerId

/**
 * Runtime instance of a [StatusEffectSpec] applied to a player.
 *
 * Carries countdown + stack state, but no behavior. Tick decrement and stack
 * resolution belong to application-layer use cases that operate on these
 * values.
 */
public data class StatusEffectInstance(
    val spec: StatusEffectSpec,
    val targetPlayerId: PlayerId,
    val remainingTicks: Int,
    val currentStacks: Int,
    val appliedAtTick: Long,
) {
    init {
        require(remainingTicks >= 0) { "remainingTicks must be >= 0: $remainingTicks" }
        require(currentStacks >= 1) { "currentStacks must be >= 1: $currentStacks" }
    }
}

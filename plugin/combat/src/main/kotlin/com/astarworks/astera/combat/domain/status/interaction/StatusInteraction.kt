package com.astarworks.astera.combat.domain.status.interaction

import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * Result of resolving an incoming status-effect application against the
 * effects already on the target (Wave 5o, ADR-0081).
 *
 * Sealed so [IStatusEffectInteractionRule] callers branch exhaustively.
 *
 * # Variant semantics
 *
 *  - [Cancel]: drop the incoming effect AND remove an existing effect by id.
 *    Authoring intent: "fire cancels frost" — applying `burn` to a player
 *    that already has `freeze` cancels both. The caller fires
 *    `StatusEffectCancelled(playerId, incomingEffectId, cancelledEffectId)`.
 *  - [Suppress]: drop the incoming effect entirely for [durationTicks]
 *    server ticks. Authoring intent: "immunity window" — the player is
 *    immune to a re-application for N ticks after a related effect lands.
 *    The container schedules a one-shot task to clear the immunity flag.
 *  - [Amplify]: keep the incoming effect, AND scale an existing effect's
 *    magnitude by [multiplier]. Authoring intent: "lightning amplifies wet"
 *    — apply lightning to a wet target → wet's slow effect doubles.
 *  - [Pass]: no interaction, apply the incoming effect normally.
 */
public sealed interface StatusInteraction {

    public data class Cancel(public val cancelledEffectId: StatusEffectId) : StatusInteraction

    public data class Suppress(
        public val suppressedEffectId: StatusEffectId,
        public val durationTicks: Int,
    ) : StatusInteraction {
        init {
            require(durationTicks > 0) { "Suppress.durationTicks must be > 0: $durationTicks" }
        }
    }

    public data class Amplify(
        public val targetEffectId: StatusEffectId,
        public val multiplier: Double,
    ) : StatusInteraction {
        init {
            require(multiplier > 0.0 && multiplier.isFinite()) {
                "Amplify.multiplier must be > 0 and finite: $multiplier"
            }
        }
    }

    public data object Pass : StatusInteraction
}

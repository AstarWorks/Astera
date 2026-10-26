package com.astarworks.astera.core.application.usecase.survival

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.survival.GatheringSkillTrigger
import com.astarworks.astera.core.domain.survival.GatheringSpotId

/**
 * Expected failure modes of [HarvestGatheringSpotUseCase] (Wave
 * 5pve-survival / ADR-0107).
 */
public sealed class GatheringError {
    public abstract val messageKey: MessageKey

    /** Spot id resolves to no registry entry. */
    public data class UnknownSpot(val spotId: GatheringSpotId) : GatheringError() {
        override val messageKey: MessageKey =
            MessageKey("astera.survival.gathering.error.unknown-spot")
    }

    /** Trigger is not valid for the spot's kind (e.g. AxeSwing on a Mining vein). */
    public data class WrongTrigger(
        val spotId: GatheringSpotId,
        val expected: String,
        val actual: GatheringSkillTrigger,
    ) : GatheringError() {
        override val messageKey: MessageKey =
            MessageKey("astera.survival.gathering.error.wrong-trigger")
    }

    /** Per-spot global cooldown is still active. */
    public data class SpotOnCooldown(val spotId: GatheringSpotId, val remainingTicks: Long) : GatheringError() {
        override val messageKey: MessageKey =
            MessageKey("astera.survival.gathering.error.spot-on-cooldown")
    }

    /** Per-player cooldown is still active. */
    public data class PlayerOnCooldown(val spotId: GatheringSpotId, val remainingTicks: Long) : GatheringError() {
        override val messageKey: MessageKey =
            MessageKey("astera.survival.gathering.error.player-on-cooldown")
    }
}

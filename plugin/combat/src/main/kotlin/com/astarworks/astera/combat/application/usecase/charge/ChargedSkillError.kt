package com.astarworks.astera.combat.application.usecase.charge

import com.astarworks.astera.combat.domain.skill.SkillId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Expected failure modes of [ChargedSkillCastUseCase].
 *
 * Distinct from `CastSkillError` because the charge-orchestration layer has
 * its own pre-conditions (skill must declare `chargeVariant = Charged`) that
 * are separate from the instant-cast pipeline's checks (cooldown, silence,
 * mana).
 */
public sealed class ChargedSkillError {
    /** [skillId] is not registered with the `ISkillRegistry`. */
    public data class UnknownSkill(val skillId: SkillId) : ChargedSkillError()

    /**
     * The skill exists but declares `ChargedSkillVariant.Instant`, so it
     * cannot be invoked through the charge-start entry point.
     */
    public data class NotAChargedSkill(val skillId: SkillId) : ChargedSkillError()

    /**
     * No active charge exists for the player at the moment a release /
     * cancel was requested.
     */
    public data class NoActiveCharge(val playerId: PlayerId) : ChargedSkillError()
}

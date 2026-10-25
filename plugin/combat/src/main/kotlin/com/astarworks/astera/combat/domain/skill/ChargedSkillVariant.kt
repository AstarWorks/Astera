package com.astarworks.astera.combat.domain.skill

/**
 * Hold-to-cast behavior of a skill.
 *
 * Wave 5a introduces two variants; richer modes (e.g. variable-tier charge
 * where damage scales with charge percent) can be added later as additional
 * sealed cases without breaking `SkillSpec.chargeVariant` callers.
 *
 *  - [Instant] — fire immediately on trigger (default for legacy skills).
 *  - [Charged] — accumulate for [Charged.chargeTicks] before firing. The
 *    application-layer `ChargedSkillCastUseCase` orchestrates the charge
 *    phase via `IScheduler.runTicks`.
 */
public sealed class ChargedSkillVariant {
    public data object Instant : ChargedSkillVariant()

    public data class Charged(val chargeTicks: Long) : ChargedSkillVariant() {
        init {
            require(chargeTicks > 0) { "Charged.chargeTicks must be > 0: $chargeTicks" }
        }
    }
}

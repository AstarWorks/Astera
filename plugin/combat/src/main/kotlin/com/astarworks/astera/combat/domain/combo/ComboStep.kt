package com.astarworks.astera.combat.domain.combo

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * One link in an ongoing combo (Wave 5combat-depth, ADR-0105).
 *
 * A combo is a chain of player actions — hits, skill casts — recorded in
 * sequence by [com.astarworks.astera.combat.application.port.outbound.IComboTracker].
 * Each step carries its own server tick so the tracker can apply a
 * timeout decay (default 60 ticks ≈ 3 s) and so downstream consumers can
 * audit the timing window.
 *
 * # Why a sealed interface
 *
 * Combo patterns mix hit-driven and skill-driven progressions (fighter-type
 * 5-hit chains vs. spell rotation chains). A sealed interface keeps the
 * vocabulary closed at compile time so route matching is exhaustive without
 * a fallback branch, and so future variants (perfect-dodge, parry, headshot)
 * land via supersede ADR instead of widening this record.
 *
 * # Why ticks not Instants
 *
 * Combo decay is measured in **server ticks** because that's the resolution
 * the Paper main thread guarantees; wall-clock instants would re-introduce
 * jitter from GC pauses. The pattern matches every other Astera domain that
 * cares about gameplay timing (cooldowns, status durations).
 */
public sealed interface ComboStep {

    /** Server tick at which this step happened. Tracker drives decay off this. */
    public val atTick: Long

    /**
     * A registered hit landed on [victim] dealing [damageDealt] raw damage
     * (post-resolve, post-mitigation — the value the victim actually lost).
     */
    public data class Hit(
        public val victim: PlayerId,
        public val damageDealt: Double,
        public override val atTick: Long,
    ) : ComboStep {
        init {
            require(atTick >= 0L) { "ComboStep.Hit.atTick must be >= 0: $atTick" }
            require(damageDealt.isFinite() && damageDealt >= 0.0) {
                "ComboStep.Hit.damageDealt must be finite and >= 0: $damageDealt"
            }
        }
    }

    /**
     * A skill was successfully cast as part of the combo. Failed casts (mana
     * insufficient, on cooldown) MUST NOT produce a step — the tracker rejects
     * them at the call site.
     */
    public data class SkillCast(
        public val skill: SkillId,
        public override val atTick: Long,
    ) : ComboStep {
        init {
            require(atTick >= 0L) { "ComboStep.SkillCast.atTick must be >= 0: $atTick" }
        }
    }
}

package com.astarworks.astera.core.domain.effect.chain

import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Leaf nodes inside an [EffectChain]. Each step is **atomic** from the
 * orchestrator's perspective: it dispatches to one outbound port and
 * returns immediately. Composition (`Sequence`, `Parallel`, `Loop`) lives
 * in [EffectChain] proper.
 *
 * Wave 5effects (ADR-0109).
 *
 * # Status / Damage steps + cross-plugin boundary
 *
 * Status effect application and damage application live in `:plugin:combat`
 * (Konsist enforces no `combat.*` import from core). The orchestrator
 * resolves these two steps through pluggable `IStatusEffectApplier` /
 * `IDamageApplier` SPIs registered by the combat plugin at `onEnable`;
 * core ships a no-op default that warn-logs when invoked. This keeps
 * `EffectChain` authoring purely declarative regardless of whether combat
 * is loaded.
 */
public sealed interface EffectStep {

    public data class ParticleStep(
        public val particle: ParticleSpec,
        public val position: Vec3,
        public val world: WorldId? = null,
    ) : EffectStep

    public data class SoundStep(
        public val sound: SoundSpec,
        public val position: Vec3,
        public val world: WorldId? = null,
    ) : EffectStep

    /**
     * Apply [effectId] to [target] for [durationTicks]. If [target] is null
     * the orchestrator drops the step with a warn-log (caller is expected
     * to supply a target at chain-build time — orchestration is not the
     * place to compute targeting).
     */
    public data class StatusEffectStep(
        public val effectId: StatusEffectId,
        public val target: PlayerId?,
        public val durationTicks: Int,
    ) : EffectStep {
        init {
            require(durationTicks >= 0) { "durationTicks must be >= 0, got $durationTicks" }
        }
    }

    /**
     * Apply [profile] to [target]. The orchestrator routes through the
     * combat-side `IDamageApplier` SPI; without combat, the step warn-logs
     * and continues (same posture as [StatusEffectStep]).
     */
    public data class DamageStep(
        public val profile: DamageProfile,
        public val target: PlayerId?,
    ) : EffectStep

    /**
     * Wait [durationTicks] before the next sibling in a [EffectChain.Sequence].
     * In a [EffectChain.Parallel] branch the delay sleeps that branch only.
     */
    public data class DelayStep(public val durationTicks: Long) : EffectStep {
        init {
            require(durationTicks >= 0) { "durationTicks must be >= 0, got $durationTicks" }
        }
    }
}

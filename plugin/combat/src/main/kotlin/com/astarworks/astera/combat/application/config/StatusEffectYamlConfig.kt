package com.astarworks.astera.combat.application.config

import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/status/` definition files (Stage 1 J of the
 * Phase 2 mid Batch 2 plan).
 *
 * Kept separate from [StatusEffectSpec] so the domain stays free of
 * serialization annotations (Konsist enforces this; see ADR-0016).
 *
 * `default_duration_ticks` is authoring metadata: the domain spec itself
 * carries no duration (duration lives on each
 * [com.astarworks.astera.combat.domain.status.StatusEffectInstance]),
 * but YAML authors think in "burn lasts 60 ticks by default", so we accept it
 * here and surface it to callers via [defaultDurationTicks]. Skill / projectile
 * pipelines that apply an effect pass this as the instance's `remainingTicks`.
 *
 * `params` is a sealed hierarchy ([StatusEffectParamsYaml]) keyed by the
 * `type:` discriminator — kotlinx-JSON convention, also kaml's configured
 * `PolymorphismStyle.Property`. Each variant carries only the fields its
 * [StatusKind] family needs.
 */
@Serializable
public data class StatusEffectYamlConfig(
    val id: String,
    @SerialName("display_name_key") val displayNameKey: String,
    val kind: String,
    @SerialName("default_duration_ticks") val defaultDurationTicks: Int,
    val stackable: Boolean = false,
    @SerialName("max_stacks") val maxStacks: Int = 1,
    val params: StatusEffectParamsYaml,
) {
    /**
     * Materialize this DTO into the domain [StatusEffectSpec].
     *
     * Throws [IllegalArgumentException] if [kind] is unknown or if [params]
     * does not match [kind] (e.g. `kind: burn` paired with `type: speed`).
     */
    public fun toSpec(): StatusEffectSpec {
        val parsedKind = StatusKind.valueOf(kind.uppercase())
        require(params.matches(parsedKind)) {
            "StatusEffect '$id' kind=$parsedKind does not match params=${params::class.simpleName}"
        }
        return StatusEffectSpec(
            id = StatusEffectId(id),
            displayNameKey = MessageKey(displayNameKey),
            kind = parsedKind,
            magnitude = params.magnitude(),
            stackable = stackable,
            maxStacks = maxStacks,
        )
    }
}

/**
 * Typed parameter payload for a status effect. The shape mirrors how the
 * effect is *interpreted* per [StatusKind] (multiplier, absolute amount, or
 * ignored for action-disabling kinds).
 *
 * Polymorphic by `type:` discriminator. Variants are intentionally coarse —
 * one per family of magnitudes — so adding a new [StatusKind] usually means
 * picking an existing variant rather than coining a new one.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class StatusEffectParamsYaml {

    /** The magnitude this variant projects onto the domain spec. */
    public abstract fun magnitude(): Double

    /** Compatibility check used by [StatusEffectYamlConfig.toSpec]. */
    public abstract fun matches(kind: StatusKind): Boolean

    /**
     * Periodic-damage style: `BURN`. `damage_per_tick` is the magnitude the
     * application-layer tick handler reads to deal damage each tick the
     * effect is active.
     */
    @Serializable
    @SerialName("burn")
    public data class BurnParamsYaml(
        @SerialName("damage_per_tick") val damagePerTick: Double,
    ) : StatusEffectParamsYaml() {
        override fun magnitude(): Double = damagePerTick
        override fun matches(kind: StatusKind): Boolean = kind == StatusKind.BURN
    }

    /**
     * Movement-speed shift: `SPEED_UP` / `SPEED_DOWN`. `multiplier` is the
     * scalar applied to base move speed.
     */
    @Serializable
    @SerialName("speed")
    public data class SpeedParamsYaml(
        val multiplier: Double,
    ) : StatusEffectParamsYaml() {
        override fun magnitude(): Double = multiplier
        override fun matches(kind: StatusKind): Boolean =
            kind == StatusKind.SPEED_UP || kind == StatusKind.SPEED_DOWN
    }

    /**
     * Heal modifier: `HEAL_AMP` / `HEAL_REDUCE` / `HEAL_NULL`. `multiplier`
     * scales incoming heal amounts (0.0 == null).
     */
    @Serializable
    @SerialName("heal")
    public data class HealParamsYaml(
        val multiplier: Double,
    ) : StatusEffectParamsYaml() {
        override fun magnitude(): Double = multiplier
        override fun matches(kind: StatusKind): Boolean = when (kind) {
            StatusKind.HEAL_AMP, StatusKind.HEAL_REDUCE, StatusKind.HEAL_NULL -> true
            else -> false
        }
    }

    /**
     * Star-gain modifier: `STAR_GAIN` / `STAR_GAIN_BOOST` / `STAR_GAIN_NULL`.
     * `amount` is interpreted as an absolute value per ADR-aligned
     * [StatusEffectSpec.magnitude] semantics.
     */
    @Serializable
    @SerialName("star_gain")
    public data class StarGainParamsYaml(
        val amount: Double,
    ) : StatusEffectParamsYaml() {
        override fun magnitude(): Double = amount
        override fun matches(kind: StatusKind): Boolean = when (kind) {
            StatusKind.STAR_GAIN, StatusKind.STAR_GAIN_BOOST, StatusKind.STAR_GAIN_NULL -> true
            else -> false
        }
    }

    /**
     * Gravity / CT-rate shift: `GRAVITY_UP` / `GRAVITY_DOWN` / `CT_RATE_UP` /
     * `CT_RATE_DOWN`. `multiplier` scales the relevant rate.
     */
    @Serializable
    @SerialName("rate")
    public data class RateParamsYaml(
        val multiplier: Double,
    ) : StatusEffectParamsYaml() {
        override fun magnitude(): Double = multiplier
        override fun matches(kind: StatusKind): Boolean = when (kind) {
            StatusKind.GRAVITY_UP,
            StatusKind.GRAVITY_DOWN,
            StatusKind.CT_RATE_UP,
            StatusKind.CT_RATE_DOWN,
            -> true
            else -> false
        }
    }

    /**
     * Action-disabling state with no scalar payload: `PARALYSIS`, `CT_FREEZE`,
     * `JUMP_DISABLED`, `SILENCE`. `magnitude()` returns 0.0 (ignored by the domain spec
     * per [StatusKind] semantics).
     */
    @Serializable
    @SerialName("disable")
    public class DisableParamsYaml : StatusEffectParamsYaml() {
        override fun magnitude(): Double = 0.0
        override fun matches(kind: StatusKind): Boolean = when (kind) {
            StatusKind.PARALYSIS, StatusKind.CT_FREEZE, StatusKind.JUMP_DISABLED, StatusKind.SILENCE -> true
            else -> false
        }

        override fun equals(other: Any?): Boolean = other is DisableParamsYaml
        override fun hashCode(): Int = javaClass.hashCode()
        override fun toString(): String = "DisableParamsYaml"
    }

    /**
     * Pure stack counter: `STACK_DEBUFF` / `STACK_BUFF`. No scalar payload; the stack count itself is
     * the value (read by `has_status.min_stacks` / `damage.per_stack`).
     */
    @Serializable
    @SerialName("marker")
    public class MarkerParamsYaml : StatusEffectParamsYaml() {
        override fun magnitude(): Double = 0.0
        override fun matches(kind: StatusKind): Boolean =
            kind == StatusKind.STACK_DEBUFF || kind == StatusKind.STACK_BUFF

        override fun equals(other: Any?): Boolean = other is MarkerParamsYaml
        override fun hashCode(): Int = javaClass.hashCode()
        override fun toString(): String = "MarkerParamsYaml"
    }
}

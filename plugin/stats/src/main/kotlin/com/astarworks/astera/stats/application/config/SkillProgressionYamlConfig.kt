package com.astarworks.astera.stats.application.config

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillPerkEffect
import com.astarworks.astera.core.domain.progression.SkillProgressionDefinition
import com.astarworks.astera.core.domain.progression.SkillProgressionPerk
import com.astarworks.astera.core.domain.progression.SkillXpCurve
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML DTO for `content/skills-progression/<axis>.yaml`
 * (Wave 5multi-skill, ADR-0101).
 *
 * Layout (per ADR-0101 §"Perk tree YAML"):
 * ```yaml
 * skill: combat                # SkillKind token
 * curve:                       # optional — defaults to exponential
 *   thresholds: [100, 224, ...]
 * perks:
 *   - level: 5
 *     id: combat-damage-1
 *     display_name_key: astera.progression.combat.damage_1
 *     effect:
 *       type: stat_multiplier
 *       stat: damage
 *       multiplier: 1.05
 *   - level: 25
 *     id: combat-crit-2
 *     effect:
 *       type: stat_additive
 *       stat: crit_chance
 *       additive: 0.10
 * ```
 *
 * Kept separate from [SkillProgressionDefinition] so the domain stays
 * serialization-annotation-free (Konsist enforces this).
 */
@Serializable
public data class SkillProgressionYamlConfig(
    val skill: String,
    val curve: SkillXpCurveYaml? = null,
    val perks: List<SkillProgressionPerkYaml> = emptyList(),
) {
    public fun toSpec(): SkillProgressionDefinition {
        val kind = SkillKind.fromToken(skill)
            ?: throw IllegalArgumentException(
                "Unknown skill axis token '$skill'. Accepted: " +
                    SkillKind.entries.joinToString(prefix = "[", postfix = "]") { it.token },
            )
        val curveSpec = curve?.toDomain() ?: SkillXpCurve.exponential()
        val perkSpecs = perks.map { it.toDomain() }
        return SkillProgressionDefinition(kind, curveSpec, perkSpecs)
    }
}

@Serializable
public data class SkillXpCurveYaml(
    val thresholds: List<Long>? = null,
    val base: Long? = null,
    val factor: Double? = null,
    @SerialName("max_level") val maxLevel: Int? = null,
) {
    public fun toDomain(): SkillXpCurve =
        if (thresholds != null) {
            SkillXpCurve(thresholds)
        } else {
            SkillXpCurve.exponential(
                base = base ?: 100L,
                factor = factor ?: 1.12,
                maxLevel = maxLevel ?: 100,
            )
        }
}

@Serializable
public data class SkillProgressionPerkYaml(
    val id: String,
    val level: Int,
    val effect: SkillPerkEffectYaml,
    @SerialName("display_name_key") val displayNameKey: String? = null,
) {
    public fun toDomain(): SkillProgressionPerk = SkillProgressionPerk(
        id = id,
        level = level,
        effect = effect.toDomain(),
        displayKey = displayNameKey?.let { MessageKey(it) },
    )
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class SkillPerkEffectYaml {

    public abstract fun toDomain(): SkillPerkEffect

    @Serializable
    @SerialName("stat_multiplier")
    public data class StatMultiplierYaml(val stat: String, val multiplier: Double) : SkillPerkEffectYaml() {
        override fun toDomain(): SkillPerkEffect = SkillPerkEffect.StatMultiplier(stat, multiplier)
    }

    @Serializable
    @SerialName("stat_additive")
    public data class StatAdditiveYaml(val stat: String, val additive: Double) : SkillPerkEffectYaml() {
        override fun toDomain(): SkillPerkEffect = SkillPerkEffect.StatAdditive(stat, additive)
    }

    @Serializable
    @SerialName("unlock_feature")
    public data class UnlockFeatureYaml(@SerialName("feature_id") val featureId: String) : SkillPerkEffectYaml() {
        override fun toDomain(): SkillPerkEffect = SkillPerkEffect.UnlockFeature(featureId)
    }
}

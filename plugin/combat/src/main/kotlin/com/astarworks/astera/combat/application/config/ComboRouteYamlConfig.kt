package com.astarworks.astera.combat.application.config

import com.astarworks.astera.combat.domain.combo.ComboPattern
import com.astarworks.astera.combat.domain.combo.ComboRoute
import com.astarworks.astera.combat.domain.combo.ComboRouteId
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YAML-shaped DTO for `content/combat-depth/combos/<id>.yaml`
 * (Wave 5combat-depth, ADR-0105).
 *
 * Konsist forbids `kotlinx.serialization` annotations in `*.domain.*`, so
 * this DTO lives in `application.config.*`. [toEntry] returns the
 * `(ComboRouteId, ComboRoute)` pair the registry consumes.
 *
 * # Wire format
 *
 * ```yaml
 * id: sword-finisher
 * weapon_id: iron-sword
 * pattern:
 *   type: hit_count
 *   target: 5
 * finisher: sword-uppercut
 * bonus_damage_multiplier: 1.5
 * ```
 *
 * # Why a discriminated `pattern` block
 *
 * Combo patterns are a sealed hierarchy (hit_count / skill_sequence /
 * timing_window). The DTO uses a single optional bag with a `type`
 * discriminator so YAML files stay flat and the conversion to
 * [ComboPattern] is one `when`. Authoring tools (Studio) can autocomplete
 * the `type` field against the fixed vocabulary.
 *
 * # bonus_damage_multiplier range
 *
 * Cap is enforced at the domain level ([ComboRoute.MIN_MULTIPLIER] /
 * [ComboRoute.MAX_MULTIPLIER]). YAML authors get a clear `require` error
 * pointing at the offending file.
 */
@Serializable
public data class ComboRouteYamlConfig(
    public val id: String,
    @SerialName("weapon_id") public val weaponId: String,
    public val pattern: ComboPatternYaml,
    public val finisher: String,
    @SerialName("bonus_damage_multiplier") public val bonusDamageMultiplier: Double = 1.0,
) {
    public fun toEntry(): Pair<ComboRouteId, ComboRoute> {
        val routeId = ComboRouteId(id)
        val route = ComboRoute(
            id = routeId,
            weaponId = ItemId(weaponId),
            pattern = pattern.toDomain(routeId),
            finisher = SkillId(finisher),
            bonusDamageMultiplier = bonusDamageMultiplier,
        )
        return routeId to route
    }
}

/**
 * Discriminated-union DTO for [ComboPattern]. `type` selects the variant.
 *
 * Variants:
 *  - `hit_count` requires `target` (Int).
 *  - `skill_sequence` requires `skills` (List<String>, lower-kebab SkillId).
 *  - `timing_window` requires `hits` (Int) + `window_ticks` (Int).
 */
@Serializable
public data class ComboPatternYaml(
    public val type: String,
    public val target: Int? = null,
    public val skills: List<String>? = null,
    public val hits: Int? = null,
    @SerialName("window_ticks") public val windowTicks: Int? = null,
) {
    public fun toDomain(routeId: ComboRouteId): ComboPattern {
        return when (type.lowercase()) {
            "hit_count" -> ComboPattern.HitCount(
                target = requireNotNull(target) {
                    "ComboRouteYamlConfig(${routeId.value}).pattern.hit_count requires target"
                },
            )
            "skill_sequence" -> ComboPattern.SkillSequence(
                skills = requireNotNull(skills) {
                    "ComboRouteYamlConfig(${routeId.value}).pattern.skill_sequence requires skills"
                }.map { SkillId(it) },
            )
            "timing_window" -> ComboPattern.TimingWindow(
                hits = requireNotNull(hits) {
                    "ComboRouteYamlConfig(${routeId.value}).pattern.timing_window requires hits"
                },
                windowTicks = requireNotNull(windowTicks) {
                    "ComboRouteYamlConfig(${routeId.value}).pattern.timing_window requires window_ticks"
                },
            )
            else -> throw IllegalArgumentException(
                "ComboRouteYamlConfig(${routeId.value}).pattern.type='$type' unknown; " +
                    "expected hit_count / skill_sequence / timing_window.",
            )
        }
    }
}

package com.astarworks.astera.combat.application.config

import com.astarworks.astera.combat.domain.skill.ChargedSkillVariant
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.combat.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.skill.SkillId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/skills/` definition files.
 *
 * Kept separate from [SkillSpec] so the domain stays free of serialization
 * annotations (Konsist enforces this; see `principles.md` §6 and ADR-0016).
 * `archetype` and `params.type` are validated to be consistent in [toSpec] —
 * authoring an `archetype: projectile` with `params.type: buff` fails fast
 * with [IllegalArgumentException] at load time so YAML typos surface in the
 * `ContentLoader` failure count rather than as runtime nulls.
 *
 * Wire format (YAML / TOML / JSON) is decided by `ContentLoader` per ADR-0016;
 * the discriminator key is `type` for kotlinx-JSON and matches kaml's
 * `PolymorphismStyle.Property` configuration so the same DTO works across
 * formats.
 *
 * **Wave _items-1 (ADR-0073)**: `trigger` removed (binding is item-side now);
 * `display_name_key` / `description_key` replaced by inline `name:` /
 * `description:` maps from locale-code → string. Each skill YAML is now
 * self-contained — no sibling write into `content/languages/<locale>.yaml` is
 * required. Unknown locale codes round-trip into [LocalizedText]; the
 * application's [com.astarworks.astera.core.application.port.outbound.IMessageRenderer]
 * picks the best match for the requesting player's locale.
 */
@Serializable
public data class SkillYamlConfig(
    val id: String,
    val name: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val archetype: String,
    @SerialName("cooldown_ticks") val cooldownTicks: Int,
    val params: SkillParamsYaml,
    @SerialName("mana_cost") val manaCost: Long? = null,
    @SerialName("charge_ticks") val chargeTicks: Long? = null,
) {
    /**
     * Materialize this DTO into the domain [SkillSpec].
     *
     * Throws [IllegalArgumentException] if the [archetype] string is unknown or
     * if [params] is not the variant that matches [archetype] (e.g.
     * `archetype: projectile` with `params: BuffParamsYaml`).
     */
    public fun toSpec(): SkillSpec {
        val parsedArchetype = SkillArchetype.valueOf(archetype.uppercase())
        val domainParams = params.toDomain()
        require(matches(parsedArchetype, domainParams)) {
            "Skill '$id' archetype=$parsedArchetype does not match params=${domainParams::class.simpleName}"
        }
        val variant = chargeTicks
            ?.let { ChargedSkillVariant.Charged(it) }
            ?: ChargedSkillVariant.Instant
        return SkillSpec(
            id = SkillId(id),
            name = name.toLocalizedText(),
            description = description.toLocalizedText(),
            archetype = parsedArchetype,
            cooldownTicks = cooldownTicks,
            params = domainParams,
            manaCost = manaCost,
            chargeVariant = variant,
        )
    }

    private fun matches(archetype: SkillArchetype, params: SkillParams): Boolean = when (archetype) {
        SkillArchetype.PROJECTILE -> params is SkillParams.ProjectileParams
        SkillArchetype.MELEE -> params is SkillParams.MeleeParams
        SkillArchetype.AOE -> params is SkillParams.AoeParams
        SkillArchetype.BUFF -> params is SkillParams.BuffParams
        SkillArchetype.TELEPORT -> params is SkillParams.TeleportParams
        SkillArchetype.SUMMON -> params is SkillParams.SummonParams
    }

    private fun Map<String, String>.toLocalizedText(): LocalizedText =
        LocalizedText(mapKeys { (code, _) -> LocaleCode.parse(code) })
}

/**
 * YAML-shaped DTO for the `damage:` block inside skill params.
 *
 * Restated here (rather than reusing `DamageYamlConfig` from
 * `WeaponYamlConfig`) so the skill schema can evolve independently of the
 * weapon one. Both project onto [DamageProfile] via [toDomain].
 */
@Serializable
public data class SkillDamageYaml(
    val base: Double,
    val attribute: String,
) {
    public fun toDomain(): DamageProfile = DamageProfile(
        base = base,
        attribute = DamageAttribute.valueOf(attribute.uppercase()),
    )
}

/**
 * Typed parameter payload for a skill, matching the sealed
 * [com.astarworks.astera.combat.domain.skill.params.SkillParams] hierarchy.
 *
 * Polymorphic by the `type:` discriminator (kotlinx-JSON convention; kaml's
 * `PolymorphismStyle.Property` reads the same property). Each variant carries
 * only the fields its archetype needs.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class SkillParamsYaml {

    public abstract fun toDomain(): SkillParams

    @Serializable
    @SerialName("melee")
    public data class MeleeParamsYaml(
        val range: Double,
        val knockback: Double,
        val damage: SkillDamageYaml,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams =
            SkillParams.MeleeParams(range = range, knockback = knockback, damage = damage.toDomain())
    }

    @Serializable
    @SerialName("projectile")
    public data class ProjectileParamsYaml(
        @SerialName("launch_speed") val launchSpeed: Double,
        @SerialName("max_lifetime_ticks") val maxLifetimeTicks: Int,
        val damage: SkillDamageYaml,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.ProjectileParams(
            launchSpeed = launchSpeed,
            maxLifetimeTicks = maxLifetimeTicks,
            damage = damage.toDomain(),
        )
    }

    @Serializable
    @SerialName("aoe")
    public data class AoeParamsYaml(
        val radius: Double,
        val damage: SkillDamageYaml,
        val falloff: String,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.AoeParams(
            radius = radius,
            damage = damage.toDomain(),
            falloff = Easing.valueOf(falloff.uppercase()),
        )
    }

    @Serializable
    @SerialName("buff")
    public data class BuffParamsYaml(
        @SerialName("status_effect_id") val statusEffectId: String,
        @SerialName("duration_ticks") val durationTicks: Int,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.BuffParams(
            statusEffectId = StatusEffectId(statusEffectId),
            durationTicks = durationTicks,
        )
    }

    @Serializable
    @SerialName("teleport")
    public data class TeleportParamsYaml(
        @SerialName("max_distance") val maxDistance: Double,
        @SerialName("requires_line_of_sight") val requiresLineOfSight: Boolean,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.TeleportParams(
            maxDistance = maxDistance,
            requiresLineOfSight = requiresLineOfSight,
        )
    }

    @Serializable
    @SerialName("summon")
    public data class SummonParamsYaml(
        @SerialName("entity_type_key") val entityTypeKey: String,
        val count: Int,
        @SerialName("lifetime_ticks") val lifetimeTicks: Int,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.SummonParams(
            entityTypeKey = entityTypeKey,
            count = count,
            lifetimeTicks = lifetimeTicks,
        )
    }
}

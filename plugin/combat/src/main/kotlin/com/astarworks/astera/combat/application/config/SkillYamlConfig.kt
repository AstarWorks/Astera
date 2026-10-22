package com.astarworks.astera.combat.application.config

import com.astarworks.astera.combat.domain.skill.ChargedSkillVariant
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.SkillTag
import com.astarworks.astera.combat.domain.projectile.DisplaySpec
import com.astarworks.astera.combat.domain.projectile.DisplayVariant
import com.astarworks.astera.combat.domain.skill.params.ProjectileVisualParams
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.config.skill.DamageComponentYaml
import com.astarworks.astera.core.application.config.skill.PhaseMechanicsYaml
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.resource.ResourceType
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.status.StatusEffectId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * YAML-shaped DTO for `content/skills/` definition files (Wave _skills-2 /
 * ADR-0077 rewrite).
 *
 * Replaces the Wave _items-1 shape that carried no phase mechanics. The
 * authoring surface is now:
 *
 * ```yaml
 * id: plasma-burst
 * archetype: projectile
 * cooldown_ticks: 4
 * resource_type: mana   # optional; default = the source item's resource_type
 * resource_cost: 2      # optional; default = the item's resource_cost_per_use; 0 = free
 * reload_ticks: 30      # optional; marks a reload skill (refills the item's ammo after N ticks)
 * ready_cue: true       # optional; sound when the cooldown ends (default: on for cooldowns >= 3 s)
 * name: { ja: "プラズマ", en: "Plasma" }
 * description: { ja: "…", en: "…" }
 * tags: [ damage, electric ]
 * params:
 *   type: projectile
 *   launch_speed: 3.0
 *   max_lifetime_ticks: 40
 *   damage:
 *     - { base: { kind: literal, value: 8.0 }, attribute: physical }
 * phases:
 *   archetype: projectile
 *   cast:    [ { type: play_sound, … } ]
 *   hit_entity:
 *     - { type: damage, targets: { kind: target }, components: [ … ] }
 *   miss: [ … ]
 *   expire: [ … ]
 * ```
 *
 * `archetype` must agree both with [params]'s variant (e.g. `projectile`
 * + `ProjectileParamsYaml`) and with [phases]'s variant (e.g. `projectile`
 * + `PhaseMechanicsYaml.Projectile`). [toSpec] enforces both pairs at parse
 * time so YAML typos surface as load failures.
 *
 * Wire format polymorphism on `params.type` and `phases.archetype` matches
 * kaml's `PolymorphismStyle.Property` (same convention used by every other
 * `*YamlConfig` in Astera).
 *
 * Compatibility: pre-release posture
 * (`/IdeaProjects/Astera/.claude/CLAUDE.md §Pre-release`). No
 * dual-decoder, no `@Deprecated` alias. Existing `content/skills/<id>.yaml`
 * files must be rewritten — that's Worker 4's territory in this wave.
 */
@Serializable
public data class SkillYamlConfig(
    public val id: String,
    public val name: Map<String, String> = emptyMap(),
    public val description: Map<String, String> = emptyMap(),
    public val archetype: String,
    @SerialName("cooldown_ticks") public val cooldownTicks: Int,
    public val params: SkillParamsYaml,
    public val phases: PhaseMechanicsYaml,
    @SerialName("resource_type") public val resourceType: String? = null,
    @SerialName("resource_cost") public val resourceCost: Int? = null,
    @SerialName("reload_ticks") public val reloadTicks: Int? = null,
    @SerialName("charge_ticks") public val chargeTicks: Long? = null,
    @SerialName("ready_cue") public val readyCue: Boolean? = null,
    public val tags: List<String> = emptyList(),
) {
    /**
     * Materialize this DTO into the domain [SkillSpec].
     *
     * Throws [IllegalArgumentException] if [archetype] is unknown or if
     * [params] / [phases] do not match it.
     */
    public fun toSpec(): SkillSpec {
        val parsedArchetype = SkillArchetype.valueOf(archetype.trim().uppercase())
        val domainParams = params.toDomain()
        val domainPhases = phases.toDomain()

        require(matchesParams(parsedArchetype, domainParams)) {
            "Skill '$id' archetype=$parsedArchetype does not match params=${domainParams::class.simpleName}"
        }
        require(matchesPhases(parsedArchetype, domainPhases)) {
            "Skill '$id' archetype=$parsedArchetype does not match phases=${domainPhases::class.simpleName}"
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
            tags = tags.map { SkillTag(it) }.toSet(),
            phases = domainPhases,
            resourceType = resourceType?.let {
                ResourceType.fromKey(it)
                    ?: throw IllegalArgumentException("Skill '$id': unknown resource_type '$it' (ammo / stamina / mana)")
            },
            resourceCost = resourceCost,
            reloadTicks = reloadTicks,
            chargeVariant = variant,
            readyCue = readyCue,
        )
    }

    private fun matchesParams(archetype: SkillArchetype, params: SkillParams): Boolean = when (archetype) {
        SkillArchetype.PROJECTILE -> params is SkillParams.ProjectileParams
        SkillArchetype.MELEE -> params is SkillParams.MeleeParams
        SkillArchetype.AOE -> params is SkillParams.AoeParams
        SkillArchetype.BUFF -> params is SkillParams.BuffParams
        SkillArchetype.TELEPORT -> params is SkillParams.TeleportParams
        SkillArchetype.SUMMON -> params is SkillParams.SummonParams
    }

    private fun matchesPhases(archetype: SkillArchetype, phases: PhaseMechanics): Boolean = when (archetype) {
        SkillArchetype.PROJECTILE -> phases is PhaseMechanics.Projectile
        SkillArchetype.MELEE -> phases is PhaseMechanics.Melee
        SkillArchetype.AOE -> phases is PhaseMechanics.Aoe
        SkillArchetype.BUFF -> phases is PhaseMechanics.Buff
        SkillArchetype.TELEPORT -> phases is PhaseMechanics.Teleport
        SkillArchetype.SUMMON -> phases is PhaseMechanics.Summon
    }

    private fun Map<String, String>.toLocalizedText(): LocalizedText =
        if (isEmpty()) LocalizedText.EMPTY
        else LocalizedText(mapKeys { (k, _) -> LocaleCode.parse(k) })
}

/**
 * Typed parameter payload for a skill, matching the sealed [SkillParams]
 * hierarchy. Wave _skills-2: every `damage:` field is now a list of
 * [DamageComponentYaml] (compound damage), and base / multiplier values use
 * [com.astarworks.astera.core.application.config.skill.NumericValueYaml]
 * (CEL-injectable).
 *
 * Polymorphic by `type:` discriminator (kaml's `PolymorphismStyle.Property`).
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class SkillParamsYaml {

    public abstract fun toDomain(): SkillParams

    @Serializable
    @SerialName("melee")
    public data class MeleeParamsYaml(
        public val range: Double,
        public val knockback: Double,
        public val damage: List<DamageComponentYaml>,
        @SerialName("arc_degrees") public val arcDegrees: Double = SkillParams.MeleeParams.DEFAULT_ARC_DEGREES,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.MeleeParams(
            range = range,
            knockback = knockback,
            damage = damage.toDamageProfile(),
            arcDegrees = arcDegrees,
        )
    }

    @Serializable
    @SerialName("projectile")
    public data class ProjectileParamsYaml(
        @SerialName("launch_speed") public val launchSpeed: Double,
        @SerialName("max_lifetime_ticks") public val maxLifetimeTicks: Int,
        @SerialName("hit_radius") public val hitRadius: Double? = null,
        public val visual: ProjectileVisualYaml? = null,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.ProjectileParams(
            launchSpeed = launchSpeed,
            maxLifetimeTicks = maxLifetimeTicks,
            hitRadius = hitRadius ?: SkillParams.ProjectileParams.DEFAULT_HIT_RADIUS,
            visual = visual?.toDomain() ?: ProjectileVisualParams.NONE,
        )
    }

    /** `params.visual` of a projectile skill: particle trail ids + optional display entity. */
    @Serializable
    public data class ProjectileVisualYaml(
        /** `content/particles` ids emitted along the path. */
        public val trail: List<String> = emptyList(),
        @SerialName("trail_interval_ticks") public val trailIntervalTicks: Int = 1,
        public val display: ProjectileDisplayYaml? = null,
    ) {
        public fun toDomain(): ProjectileVisualParams = ProjectileVisualParams(
            trail = trail.map(::ParticleId),
            trailIntervalTicks = trailIntervalTicks,
            display = display?.toDomain(),
        )
    }

    /** An item / block display entity that rides the projectile. */
    @Serializable
    public data class ProjectileDisplayYaml(
        /** `item` | `block` | `text`. */
        public val variant: String = "item",
        /** Material key (`minecraft:blaze_powder`) or, for `text`, the literal text. */
        public val material: String,
        public val scale: Double = 1.0,
    ) {
        public fun toDomain(): DisplaySpec = DisplaySpec(
            variant = DisplayVariant.valueOf(variant.uppercase() + "_DISPLAY"),
            materialKey = material,
            scale = Vec3(scale, scale, scale),
        )
    }

    @Serializable
    @SerialName("aoe")
    public data class AoeParamsYaml(
        public val radius: Double,
        public val damage: List<DamageComponentYaml>,
        public val falloff: String,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.AoeParams(
            radius = radius,
            damage = damage.toDamageProfile(),
            falloff = Easing.valueOf(falloff.uppercase()),
        )
    }

    @Serializable
    @SerialName("buff")
    public data class BuffParamsYaml(
        @SerialName("status_effect_id") public val statusEffectId: String,
        @SerialName("duration_ticks") public val durationTicks: Int,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.BuffParams(
            statusEffectId = StatusEffectId(statusEffectId),
            durationTicks = durationTicks,
        )
    }

    @Serializable
    @SerialName("teleport")
    public data class TeleportParamsYaml(
        @SerialName("max_distance") public val maxDistance: Double,
        @SerialName("requires_line_of_sight") public val requiresLineOfSight: Boolean,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.TeleportParams(
            maxDistance = maxDistance,
            requiresLineOfSight = requiresLineOfSight,
        )
    }

    @Serializable
    @SerialName("summon")
    public data class SummonParamsYaml(
        @SerialName("entity_type_key") public val entityTypeKey: String,
        public val count: Int,
        @SerialName("lifetime_ticks") public val lifetimeTicks: Int,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.SummonParams(
            entityTypeKey = entityTypeKey,
            count = count,
            lifetimeTicks = lifetimeTicks,
        )
    }

    public companion object {
        private fun List<DamageComponentYaml>.toDamageProfile(): DamageProfile {
            require(isNotEmpty()) { "SkillParamsYaml.damage must not be empty" }
            return DamageProfile(map { it.toDomain() })
        }
    }
}

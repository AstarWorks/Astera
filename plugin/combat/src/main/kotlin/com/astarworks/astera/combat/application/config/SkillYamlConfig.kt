package com.astarworks.astera.combat.application.config

import com.astarworks.astera.core.application.config.skill.toAnimation
import com.astarworks.astera.combat.domain.skill.BlockDamageSpec
import com.astarworks.astera.combat.domain.skill.BlockPlaceSpec
import com.astarworks.astera.combat.domain.skill.ChargedSkillVariant
import com.astarworks.astera.core.domain.world.ToolKind
import com.astarworks.astera.core.domain.world.ToolType
import com.astarworks.astera.combat.domain.skill.SkillArchetype
import com.astarworks.astera.combat.domain.skill.SkillSpec
import com.astarworks.astera.combat.domain.skill.SkillTag
import com.astarworks.astera.combat.domain.projectile.DisplaySpec
import com.astarworks.astera.combat.domain.projectile.DisplayVariant
import com.astarworks.astera.combat.domain.skill.params.ProjectileVisualParams
import com.astarworks.astera.core.application.config.skill.toAnimation
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
 * resource_type: energy # optional; default = the source item's resource_type
 * resource_cost: 2      # optional; default = the item's resource_cost_per_use; 0 = free
 * ego_cost: 100      # optional; E.G.O gauge points the cast consumes (refused when short)
 * reload_ticks: 30      # optional; marks a reload skill (refills the item's ammo after N ticks)
 * block_damage:       # optional; opt in to damaging cover (ADR-0280)
 *   amount: 20         # flat HP chip; with `tool` the matrix base (100 = one swing)
 *   radius: 0          # blocks around the impact; 0 = the one block struck
 *   tool: PICKAXE/IRON # optional; count as this tool (tool skills)
 *   reach: 4           # cast-time skills: look-ray length
 *   shape: ball        # ball | line
 * block_place:         # optional; raise a temporary cover wall
 *   block: astera:sandbags
 *   distance: 2
 *   width: 3
 *   height: 2
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
    @SerialName("ego_cost") public val egoCost: Int? = null,
    @SerialName("block_damage") public val blockDamage: BlockDamageYaml? = null,
    @SerialName("block_place") public val blockPlace: BlockPlaceYaml? = null,
    public val tags: List<String> = emptyList(),
    /** Telegraphed wind-up before impact (sound arc, ground warning, vfx). DTO only: desugared into cast-phase mechanics later. */
    public val windup: WindupYaml? = null,
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
                    ?: throw IllegalArgumentException("Skill '$id': unknown resource_type '$it' (ammo / energy)")
            },
            resourceCost = resourceCost,
            reloadTicks = reloadTicks,
            chargeVariant = variant,
            readyCue = readyCue,
            egoCost = egoCost,
            blockDamage = blockDamage?.toDomain(id),
            blockPlace = blockPlace?.toDomain(),
        )
    }

    private fun matchesParams(archetype: SkillArchetype, params: SkillParams): Boolean = when (archetype) {
        SkillArchetype.PROJECTILE -> params is SkillParams.ProjectileParams
        SkillArchetype.MELEE -> params is SkillParams.MeleeParams
        SkillArchetype.AOE -> params is SkillParams.AoeParams
        SkillArchetype.BUFF -> params is SkillParams.BuffParams
        SkillArchetype.TELEPORT -> params is SkillParams.TeleportParams
        SkillArchetype.SUMMON -> params is SkillParams.SummonParams
        SkillArchetype.SEQUENCE -> params is SkillParams.SequenceParams
    }

    private fun matchesPhases(archetype: SkillArchetype, phases: PhaseMechanics): Boolean = when (archetype) {
        SkillArchetype.PROJECTILE -> phases is PhaseMechanics.Projectile
        SkillArchetype.MELEE -> phases is PhaseMechanics.Melee
        SkillArchetype.AOE -> phases is PhaseMechanics.Aoe
        SkillArchetype.BUFF -> phases is PhaseMechanics.Buff
        SkillArchetype.TELEPORT -> phases is PhaseMechanics.Teleport
        SkillArchetype.SUMMON -> phases is PhaseMechanics.Summon
        SkillArchetype.SEQUENCE -> phases is PhaseMechanics.Sequence
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
        /** Blocks in front of the eye the projectile spawns at (default 0.5). */
        @SerialName("muzzle_forward") public val muzzleForward: Double? = null,
        /** Blocks above the eye the projectile spawns at (default 0). */
        @SerialName("muzzle_up") public val muzzleUp: Double? = null,
        /**
         * When set the shot is aimed at the point this many blocks along the caster's look ray, not parallel to it
         * (a projectile spawned above the eye then dives onto what the caster looks at). Absent = parallel.
         */
        @SerialName("aim_distance") public val aimDistance: Double? = null,
    ) : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.ProjectileParams(
            launchSpeed = launchSpeed,
            maxLifetimeTicks = maxLifetimeTicks,
            hitRadius = hitRadius ?: SkillParams.ProjectileParams.DEFAULT_HIT_RADIUS,
            visual = visual?.toDomain() ?: ProjectileVisualParams.NONE,
            muzzleForward = muzzleForward ?: SkillParams.ProjectileParams.DEFAULT_MUZZLE_FORWARD,
            muzzleUp = muzzleUp ?: 0.0,
            aimDistance = aimDistance ?: 0.0,
        )
    }

    /** `params.visual` of a projectile skill: particle-animation trail + optional display entity. */
    @Serializable
    public data class ProjectileVisualYaml(
        /** RTM-style particle animation replayed at the projectile every tick. */
        public val trail: List<com.astarworks.astera.core.application.config.skill.AnimatedShapeYaml>? = null,
        /** Blocks between trail plays along the tick's segment (0 = one play per tick). */
        @SerialName("trail_spacing") public val trailSpacing: Double = ProjectileVisualParams.DEFAULT_TRAIL_SPACING,
        public val display: ProjectileDisplayYaml? = null,
        /** A `content/vfx` choreography that rides the projectile for its whole flight and ends when it ends (hit, miss, expiry). */
        public val vfx: String? = null,
    ) {
        public fun toDomain(): ProjectileVisualParams = ProjectileVisualParams(
            trail = trail?.toAnimation(),
            trailSpacing = trailSpacing,
            display = display?.toDomain(),
            vfx = vfx?.let { com.astarworks.astera.core.domain.effect.vfx.VfxId(it) },
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

    /** `params: { type: sequence }` - the sequence archetype has no parameters. */
    @Serializable
    @SerialName("sequence")
    public data object SequenceParamsYaml : SkillParamsYaml() {
        override fun toDomain(): SkillParams = SkillParams.SequenceParams
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


/** YAML shape of a skill's `block_damage` (ADR-0280). */
@Serializable
public data class BlockDamageYaml(
    public val amount: Int,
    public val radius: Double = 0.0,
    public val tool: String? = null,
    public val reach: Double = 0.0,
    public val shape: String = "ball",
) {
    internal fun toDomain(skillId: String): BlockDamageSpec = BlockDamageSpec(
        amount = amount,
        radius = radius,
        tool = tool?.let {
            ToolType.fromSerialKey(it)?.takeIf { t -> t.kind != ToolKind.NONE }
                ?: throw IllegalArgumentException("Skill '$skillId': unknown block_damage.tool '$it' (e.g. PICKAXE/IRON)")
        },
        reach = reach,
        shape = BlockDamageSpec.Shape.entries.firstOrNull { it.name.equals(shape.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("Skill '$skillId': unknown block_damage.shape '$shape' (ball / line)"),
    )
}

/** YAML shape of a skill's `block_place` (ADR-0280). */
@Serializable
public data class BlockPlaceYaml(
    public val block: String,
    public val distance: Int = 2,
    public val width: Int = 3,
    public val height: Int = 2,
) {
    internal fun toDomain(): BlockPlaceSpec = BlockPlaceSpec(block, distance, width, height)
}

/**
 * Skill-level `windup:` block. [ticks] is the wind-up length: the sound / telegraph / vfx play from cast tick 0 and
 * the damaging mechanics shift by [ticks].
 */
@Serializable
public data class WindupYaml(
    public val ticks: Int,
    public val sound: WindupSoundYaml? = null,
    public val telegraph: WindupTelegraphYaml? = null,
    /** `content/vfx` id played at tick 0. */
    public val vfx: String? = null,
)

@Serializable
public data class WindupSoundYaml(
    @SerialName("sound_id") public val soundId: String,
    public val volume: Double = 1.0,
    public val pitch: Double = 1.0,
    @SerialName("pitch_arc") public val pitchArc: com.astarworks.astera.core.application.config.skill.PitchArcYaml? = null,
    /** Cut the sound at the impact tick (`stop_sound`). */
    @SerialName("stop_at_impact") public val stopAtImpact: Boolean = true,
)

@Serializable
public data class WindupTelegraphYaml(
    public val shape: String = "circle",
    public val radius: Double,
    @SerialName("angle_degrees") public val angleDegrees: Double = 60.0,
    public val width: Double = 2.0,
    @SerialName("team_color") public val teamColor: String = "enemy",
)

package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.mechanic.ScreenOverlay
import com.astarworks.astera.core.domain.status.StatusEffectId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire shape for [Mechanic]. Polymorphic by `type:` discriminator — one
 * sealed subclass per [Mechanic] variant.
 *
 * Every variant carries the four timing fields ([delayTicks] / [periodTicks]
 * / [durationTicks] / [condition]) so YAML can pin a per-mechanic schedule
 * (e.g., per-tick trail) without wrapping in a separate `Delay` / `Tick`
 * mechanic — see ADR-0077 §"Mechanic timing fields — scheduler-folded
 * design".
 *
 * Wave _skills-2 / ADR-0077.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class MechanicYaml {

    public abstract fun toDomain(): Mechanic

    // ───────────────────────── Gameplay (3) ─────────────────────────

    @Serializable
    @SerialName("damage")
    public data class Damage(
        public val targets: TargeterYaml,
        public val components: List<DamageComponentYaml>,
        @SerialName("ignore_armor") public val ignoreArmor: BoolValueYaml = BoolValueYaml.Literal(false),
        @SerialName("ignore_invulnerability") public val ignoreInvulnerability: BoolValueYaml = BoolValueYaml.Literal(false),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Damage(
            targets = targets.toDomain(),
            components = components.map { it.toDomain() },
            ignoreArmor = ignoreArmor.toDomain(),
            ignoreInvulnerability = ignoreInvulnerability.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("heal")
    public data class Heal(
        public val targets: TargeterYaml,
        public val amount: NumericValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Heal(
            targets = targets.toDomain(),
            amount = amount.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("resurrect")
    public data class Resurrect(
        public val targets: TargeterYaml,
        @SerialName("heal_fraction") public val healFraction: NumericValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Resurrect(
            targets = targets.toDomain(),
            healFraction = healFraction.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    // ───────────────────────── Status (6) ─────────────────────────

    @Serializable
    @SerialName("apply_status")
    public data class ApplyStatus(
        public val targets: TargeterYaml,
        @SerialName("status_id") public val statusId: String,
        @SerialName("effect_duration_ticks") public val effectDurationTicks: IntValueYaml,
        public val chance: NumericValueYaml = NumericValueYaml.Literal(1.0),
        @SerialName("stack_count") public val stackCount: IntValueYaml = IntValueYaml.Literal(1),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.ApplyStatus(
            targets = targets.toDomain(),
            statusId = StatusEffectId(statusId),
            effectDurationTicks = effectDurationTicks.toDomain(),
            chance = chance.toDomain(),
            stackCount = stackCount.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("remove_status")
    public data class RemoveStatus(
        public val targets: TargeterYaml,
        @SerialName("status_id") public val statusId: String,
        /** `null` removes the whole status; `N` consumes N stacks. */
        public val stacks: Int? = null,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.RemoveStatus(
            targets = targets.toDomain(),
            statusId = StatusEffectId(statusId),
            stacks = stacks?.let { com.astarworks.astera.core.domain.skill.value.IntValue.Literal(it) },
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("cleanse")
    public data class Cleanse(
        public val targets: TargeterYaml,
        @SerialName("only_harmful") public val onlyHarmful: BoolValueYaml = BoolValueYaml.Literal(true),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Cleanse(
            targets = targets.toDomain(),
            onlyHarmful = onlyHarmful.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("ignite")
    public data class Ignite(
        public val targets: TargeterYaml,
        public val ticks: IntValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Ignite(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("freeze")
    public data class Freeze(
        public val targets: TargeterYaml,
        public val ticks: IntValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Freeze(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("silence")
    public data class Silence(
        public val targets: TargeterYaml,
        public val ticks: IntValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Silence(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("restore_resource")
    public data class RestoreResource(
        /** `ammo` | `energy`. */
        public val resource: String,
        public val amount: IntValueYaml,
        public val targets: TargeterYaml = TargeterYaml.Caster,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.RestoreResource(
            resource = com.astarworks.astera.core.domain.resource.ResourceType.fromKey(resource)
                ?: throw IllegalArgumentException("restore_resource: unknown resource '$resource' (ammo / energy)"),
            amount = amount.toDomain(),
            targets = targets.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    // ───────────────────────── Movement (6) ─────────────────────────

    @Serializable
    @SerialName("knockback")
    public data class Knockback(
        public val targets: TargeterYaml,
        public val direction: DirectionRefYaml,
        public val strength: NumericValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Knockback(
            targets = targets.toDomain(),
            direction = direction.toDomain(),
            strength = strength.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("knockup")
    public data class Knockup(
        public val targets: TargeterYaml,
        public val strength: NumericValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Knockup(
            targets = targets.toDomain(),
            strength = strength.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("set_velocity")
    public data class SetVelocity(
        public val targets: TargeterYaml,
        public val direction: DirectionRefYaml,
        public val magnitude: NumericValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SetVelocity(
            targets = targets.toDomain(),
            direction = direction.toDomain(),
            magnitude = magnitude.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("propel")
    public data class Propel(
        public val targets: TargeterYaml,
        public val direction: DirectionRefYaml,
        public val strength: NumericValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Propel(
            targets = targets.toDomain(),
            direction = direction.toDomain(),
            strength = strength.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("teleport")
    public data class Teleport(
        public val targets: TargeterYaml,
        public val destination: LocationRefYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Teleport(
            targets = targets.toDomain(),
            destination = destination.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("dash_strike")
    public data class DashStrike(
        public val distance: Double,
        public val ticks: Int,
        public val radius: Double = 1.2,
        @SerialName("hit_once") public val hitOnce: Boolean = true,
        public val invulnerable: Boolean = true,
        @SerialName("stop_on_block") public val stopOnBlock: Boolean = true,
        @SerialName("follow_pitch") public val followPitch: Boolean = false,
        @SerialName("stop_before_target") public val stopBeforeTarget: Double? = null,
        @SerialName("on_hit") public val onHit: List<MechanicYaml> = emptyList(),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.DashStrike(
            distance = com.astarworks.astera.core.domain.skill.value.NumericValue.Literal(distance),
            ticks = com.astarworks.astera.core.domain.skill.value.IntValue.Literal(ticks),
            radius = com.astarworks.astera.core.domain.skill.value.NumericValue.Literal(radius),
            hitOnce = com.astarworks.astera.core.domain.skill.value.BoolValue.Literal(hitOnce),
            invulnerable = com.astarworks.astera.core.domain.skill.value.BoolValue.Literal(invulnerable),
            stopOnBlock = com.astarworks.astera.core.domain.skill.value.BoolValue.Literal(stopOnBlock),
            followPitch = com.astarworks.astera.core.domain.skill.value.BoolValue.Literal(followPitch),
            stopBeforeTarget = stopBeforeTarget,
            onHit = onHit.map { it.toDomain() },
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("hit_stop")
    public data class HitStop(
        public val targets: TargeterYaml,
        public val ticks: Int = 3,
        @SerialName("include_caster") public val includeCaster: Boolean = false,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.HitStop(
            targets = targets.toDomain(),
            ticks = com.astarworks.astera.core.domain.skill.value.IntValue.Literal(ticks),
            includeCaster = com.astarworks.astera.core.domain.skill.value.BoolValue.Literal(includeCaster),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("leap")
    public data class Leap(
        public val targets: TargeterYaml,
        public val destination: LocationRefYaml,
        @SerialName("arc_height") public val arcHeight: NumericValueYaml = NumericValueYaml.Literal(2.0),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Leap(
            targets = targets.toDomain(),
            destination = destination.toDomain(),
            arcHeight = arcHeight.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    // ───────────────────────── World (5) ─────────────────────────

    @Serializable
    @SerialName("summon_lightning")
    public data class SummonLightning(
        public val targets: TargeterYaml,
        @SerialName("visual_only") public val visualOnly: BoolValueYaml = BoolValueYaml.Literal(true),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SummonLightning(
            targets = targets.toDomain(),
            visualOnly = visualOnly.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("summon")
    public data class Summon(
        public val targets: TargeterYaml,
        @SerialName("entity_type_key") public val entityTypeKey: String,
        public val count: IntValueYaml = IntValueYaml.Literal(1),
        @SerialName("lifetime_ticks") public val lifetimeTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Summon(
            targets = targets.toDomain(),
            entityTypeKey = entityTypeKey,
            count = count.toDomain(),
            lifetimeTicks = lifetimeTicks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("set_block")
    public data class SetBlock(
        public val targets: TargeterYaml,
        @SerialName("block_key") public val blockKey: String,
        @SerialName("revert_after_ticks") public val revertAfterTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("outside_arena") public val outsideArena: BoolValueYaml = BoolValueYaml.Literal(false),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SetBlock(
            targets = targets.toDomain(),
            blockKey = blockKey,
            revertAfterTicks = revertAfterTicks.toDomain(),
            outsideArena = outsideArena.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("remove_block")
    public data class RemoveBlock(
        public val targets: TargeterYaml,
        @SerialName("revert_after_ticks") public val revertAfterTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("outside_arena") public val outsideArena: BoolValueYaml = BoolValueYaml.Literal(false),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.RemoveBlock(
            targets = targets.toDomain(),
            revertAfterTicks = revertAfterTicks.toDomain(),
            outsideArena = outsideArena.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("explode")
    public data class Explode(
        public val targets: TargeterYaml,
        public val power: NumericValueYaml,
        @SerialName("break_blocks") public val breakBlocks: BoolValueYaml = BoolValueYaml.Literal(false),
        public val incendiary: BoolValueYaml = BoolValueYaml.Literal(false),
        public val radius: NumericValueYaml? = null,
        public val components: List<DamageComponentYaml> = emptyList(),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Explode(
            targets = targets.toDomain(),
            power = power.toDomain(),
            breakBlocks = breakBlocks.toDomain(),
            incendiary = incendiary.toDomain(),
            radius = radius?.toDomain(),
            components = components.map { it.toDomain() },
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    // ───────────────────────── Item (4) ─────────────────────────

    @Serializable
    @SerialName("give_item")
    public data class GiveItem(
        public val targets: TargeterYaml,
        @SerialName("item_id") public val itemId: String,
        public val count: IntValueYaml = IntValueYaml.Literal(1),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.GiveItem(
            targets = targets.toDomain(),
            itemId = ItemId(itemId),
            count = count.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("consume_item")
    public data class ConsumeItem(
        public val targets: TargeterYaml,
        @SerialName("item_id") public val itemId: String,
        public val count: IntValueYaml = IntValueYaml.Literal(1),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.ConsumeItem(
            targets = targets.toDomain(),
            itemId = ItemId(itemId),
            count = count.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("modify_attribute")
    public data class ModifyAttribute(
        public val targets: TargeterYaml,
        @SerialName("attribute_key") public val attributeKey: String,
        public val delta: NumericValueYaml,
        public val ticks: IntValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.ModifyAttribute(
            targets = targets.toDomain(),
            attributeKey = attributeKey,
            delta = delta.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("set_cooldown")
    public data class SetCooldown(
        public val targets: TargeterYaml,
        @SerialName("skill_id") public val skillId: String,
        @SerialName("cooldown_ticks") public val cooldownTicks: IntValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SetCooldown(
            targets = targets.toDomain(),
            skillId = SkillId(skillId),
            cooldownTicks = cooldownTicks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    // ───────────────────────── Visual (6) ─────────────────────────

    @Serializable
    @SerialName("spawn_particle")
    public data class SpawnParticle(
        public val targets: TargeterYaml,
        @SerialName("particle_id") public val particleId: String,
        public val count: IntValueYaml = IntValueYaml.Literal(1),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SpawnParticle(
            targets = targets.toDomain(),
            particleId = ParticleId(particleId),
            count = count.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("schedule_particle_animation")
    public data class ScheduleParticleAnimation(
        public val targets: TargeterYaml,
        public val animation: List<AnimatedShapeYaml>,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.ScheduleParticleAnimation(
            targets = targets.toDomain(),
            animation = animation.toAnimation(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("play_sound")
    public data class PlaySound(
        public val targets: TargeterYaml,
        @SerialName("sound_id") public val soundId: String,
        public val volume: NumericValueYaml = NumericValueYaml.Literal(1.0),
        public val pitch: NumericValueYaml = NumericValueYaml.Literal(1.0),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.PlaySound(
            targets = targets.toDomain(),
            soundId = SoundId(soundId),
            volume = volume.toDomain(),
            pitch = pitch.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    /**
     * Display Entity spawn. [display] inlines the [DisplaySpecYaml] DTO since
     * `DisplaySpec` has no domain-side YAML mirror yet — Worker 2 owns the
     * domain type, but the YAML projection has to live here for the
     * polymorphic discriminator to resolve.
     */
    @Serializable
    @SerialName("spawn_display")
    public data class SpawnDisplay(
        public val targets: TargeterYaml,
        public val display: DisplaySpecYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SpawnDisplay(
            targets = targets.toDomain(),
            display = display.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    /** Plays a `content/vfx` display choreography (ADR-0220). */
    @Serializable
    @SerialName("play_vfx")
    public data class PlayVfx(
        public val targets: TargeterYaml,
        @SerialName("vfx_id") public val vfxId: String,
        public val scale: NumericValueYaml = NumericValueYaml.Literal(1.0),
        public val follow: BoolValueYaml? = null,
        /** Hit-stop windows `[{at, ticks}]` (effect ticks) freezing the rigs that set `respect_hit_stop`. */
        public val freeze: List<com.astarworks.astera.core.application.config.VfxFreezeYaml> = emptyList(),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.PlayVfx(
            targets = targets.toDomain(),
            vfxId = com.astarworks.astera.core.domain.effect.vfx.VfxId(vfxId),
            scale = scale.toDomain(),
            follow = follow?.toDomain(),
            freeze = freeze.map { it.toDomain() },
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("glow")
    public data class Glow(
        public val targets: TargeterYaml,
        public val ticks: IntValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Glow(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("hide_held_item")
    public data class HideHeldItem(
        public val targets: TargeterYaml,
        public val ticks: IntValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.HideHeldItem(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("camera_shake")
    public data class CameraShake(
        public val targets: TargeterYaml,
        public val intensity: NumericValueYaml,
        public val ticks: IntValueYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.CameraShake(
            targets = targets.toDomain(),
            intensity = intensity.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("screen_flash")
    public data class ScreenFlash(
        public val targets: TargeterYaml,
        /** One of white / vignette / gold_burst / speed_diag / speed_radial / impact_star. */
        public val overlay: String,
        /** `#RRGGBB` tint; omitted = the overlay's default colour. */
        public val color: String? = null,
        @SerialName("fade_in") public val fadeIn: IntValueYaml = IntValueYaml.Literal(0),
        public val stay: IntValueYaml = IntValueYaml.Literal(1),
        @SerialName("fade_out") public val fadeOut: IntValueYaml = IntValueYaml.Literal(2),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.ScreenFlash(
            targets = targets.toDomain(),
            overlay = requireNotNull(ScreenOverlay.fromYaml(overlay)) {
                "screen_flash: unknown overlay '$overlay' (one of ${ScreenOverlay.entries.joinToString { it.yamlName }})"
            },
            colorRgb = color?.let {
                require(HEX_COLOR.matches(it)) { "screen_flash: color '$it' is not #RRGGBB" }
                it.substring(1).toInt(16)
            },
            fadeInTicks = fadeIn.toDomain(),
            stayTicks = stay.toDomain(),
            fadeOutTicks = fadeOut.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )

        private companion object {
            val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")
        }
    }

    // ───────────────────────── Messages (3) ─────────────────────────

    @Serializable
    @SerialName("send_message")
    public data class SendMessage(
        public val targets: TargeterYaml,
        public val message: Map<String, String>,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SendMessage(
            targets = targets.toDomain(),
            message = message.toLocalizedText(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("send_action_bar")
    public data class SendActionBar(
        public val targets: TargeterYaml,
        public val message: Map<String, String>,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SendActionBar(
            targets = targets.toDomain(),
            message = message.toLocalizedText(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("send_title")
    public data class SendTitle(
        public val targets: TargeterYaml,
        public val title: Map<String, String>,
        public val subtitle: Map<String, String>? = null,
        @SerialName("fade_in_ticks") public val fadeInTicks: IntValueYaml = IntValueYaml.Literal(10),
        @SerialName("stay_ticks") public val stayTicks: IntValueYaml = IntValueYaml.Literal(40),
        @SerialName("fade_out_ticks") public val fadeOutTicks: IntValueYaml = IntValueYaml.Literal(10),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SendTitle(
            targets = targets.toDomain(),
            title = title.toLocalizedText(),
            subtitle = subtitle?.toLocalizedText(),
            fadeInTicks = fadeInTicks.toDomain(),
            stayTicks = stayTicks.toDomain(),
            fadeOutTicks = fadeOutTicks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    // ───────────────────────── Control flow (4) ─────────────────────────

    @Serializable
    @SerialName("cast_skill")
    public data class CastSkill(
        @SerialName("skill_id") public val skillId: String,
        public val targets: TargeterYaml = TargeterYaml.Caster,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.CastSkill(
            targets = targets.toDomain(),
            skillId = SkillId(skillId),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("if_then")
    public data class IfThen(
        public val predicate: ConditionYaml,
        @SerialName("then") public val then_: List<MechanicYaml>,
        public val targets: TargeterYaml = TargeterYaml.Caster,
        @SerialName("else") public val else_: List<MechanicYaml> = emptyList(),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.IfThen(
            targets = targets.toDomain(),
            predicate = predicate.toDomain(),
            then_ = then_.map { it.toDomain() },
            else_ = else_.map { it.toDomain() },
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("loop")
    public data class Loop(
        public val count: IntValueYaml,
        public val mechanics: List<MechanicYaml>,
        public val targets: TargeterYaml = TargeterYaml.Caster,
        @SerialName("stride_ticks") public val strideTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Loop(
            targets = targets.toDomain(),
            count = count.toDomain(),
            strideTicks = strideTicks.toDomain(),
            mechanics = mechanics.map { it.toDomain() },
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    @Serializable
    @SerialName("choose")
    public data class Choose(
        public val weighted: List<WeightedBranchYaml>,
        public val targets: TargeterYaml = TargeterYaml.Caster,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
        @SerialName("snapshot_pose") public val snapshotPose: Boolean = false,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Choose(
            targets = targets.toDomain(),
            weighted = weighted.map { it.toDomain() },
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
            snapshotPose = snapshotPose,
        )
    }

    public companion object {
        private fun Map<String, String>.toLocalizedText(): LocalizedText =
            if (isEmpty()) LocalizedText.EMPTY
            else LocalizedText(mapKeys { (k, _) -> LocaleCode.parse(k) })
    }
}

/**
 * Wire shape for [com.astarworks.astera.core.domain.skill.mechanic.Mechanic.WeightedBranch].
 *
 * Wave _skills-2 / ADR-0077.
 */
@Serializable
public data class WeightedBranchYaml(
    public val weight: Double,
    public val mechanics: List<MechanicYaml>,
) {
    public fun toDomain(): Mechanic.WeightedBranch = Mechanic.WeightedBranch(
        weight = weight,
        mechanics = mechanics.map { it.toDomain() },
    )
}

/**
 * Wire shape for [com.astarworks.astera.core.domain.effect.DisplaySpec].
 *
 * Authors specify `kind: { type: block, block_key: "minecraft:stone" }` /
 * `kind: { type: item, item_id: "..." }` / `kind: { type: text, lines: [...] }`,
 * then optional `transform`, required `lifetime_ticks`, optional `billboard`.
 *
 * Wave _skills-2 / ADR-0077.
 */
@Serializable
public data class DisplaySpecYaml(
    public val kind: DisplayKindYaml,
    public val transform: DisplayTransformYaml = DisplayTransformYaml(),
    @SerialName("lifetime_ticks") public val lifetimeTicks: Long,
    public val billboard: String = "fixed",
) {
    public fun toDomain(): DisplaySpec {
        val parsedBillboard = try {
            com.astarworks.astera.core.domain.effect.DisplayBillboard.valueOf(
                billboard.trim().uppercase(),
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "DisplaySpecYaml: unknown billboard '$billboard' " +
                    "(expected one of fixed/vertical/horizontal/center)",
                e,
            )
        }
        return DisplaySpec(
            kind = kind.toDomain(),
            transform = transform.toDomain(),
            lifetimeTicks = lifetimeTicks,
            billboard = parsedBillboard,
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class DisplayKindYaml {
    public abstract fun toDomain(): com.astarworks.astera.core.domain.effect.DisplayKind

    @Serializable
    @SerialName("block")
    public data class Block(@SerialName("block_key") public val blockKey: String) : DisplayKindYaml() {
        override fun toDomain(): com.astarworks.astera.core.domain.effect.DisplayKind =
            com.astarworks.astera.core.domain.effect.DisplayKind.Block(blockKey)
    }

    @Serializable
    @SerialName("item")
    public data class Item(@SerialName("item_id") public val itemId: String) : DisplayKindYaml() {
        override fun toDomain(): com.astarworks.astera.core.domain.effect.DisplayKind =
            com.astarworks.astera.core.domain.effect.DisplayKind.Item(ItemId(itemId))
    }

    @Serializable
    @SerialName("text")
    public data class Text(
        public val lines: List<Map<String, String>>,
        @SerialName("background_argb") public val backgroundArgb: Int = 0x40000000.toInt(),
    ) : DisplayKindYaml() {
        override fun toDomain(): com.astarworks.astera.core.domain.effect.DisplayKind {
            require(lines.isNotEmpty()) { "DisplayKindYaml.Text.lines must not be empty" }
            return com.astarworks.astera.core.domain.effect.DisplayKind.Text(
                lines = lines.map { line ->
                    LocalizedText(line.mapKeys { (k, _) -> LocaleCode.parse(k) })
                },
                backgroundArgb = backgroundArgb,
            )
        }
    }
}

@Serializable
public data class DisplayTransformYaml(
    public val translation: Vec3Yaml = Vec3Yaml(0.0, 0.0, 0.0),
    public val rotation: QuaternionYaml = QuaternionYaml(0.0, 0.0, 0.0, 1.0),
    public val scale: Vec3Yaml = Vec3Yaml(1.0, 1.0, 1.0),
) {
    public fun toDomain(): com.astarworks.astera.core.domain.effect.DisplayTransform =
        com.astarworks.astera.core.domain.effect.DisplayTransform(
            translation = translation.toDomain(),
            rotation = rotation.toDomain(),
            scale = scale.toDomain(),
        )
}

@Serializable
public data class QuaternionYaml(
    public val x: Double,
    public val y: Double,
    public val z: Double,
    public val w: Double,
) {
    public fun toDomain(): com.astarworks.astera.core.domain.geometry.math.Quaternion =
        com.astarworks.astera.core.domain.geometry.math.Quaternion(x, y, z, w)
}

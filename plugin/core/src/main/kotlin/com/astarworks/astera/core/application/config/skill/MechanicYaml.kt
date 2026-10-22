package com.astarworks.astera.core.application.config.skill

import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.ParticleAnimationId
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.i18n.LocaleCode
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Heal(
            targets = targets.toDomain(),
            amount = amount.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Resurrect(
            targets = targets.toDomain(),
            healFraction = healFraction.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
        )
    }

    @Serializable
    @SerialName("remove_status")
    public data class RemoveStatus(
        public val targets: TargeterYaml,
        @SerialName("status_id") public val statusId: String,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.RemoveStatus(
            targets = targets.toDomain(),
            statusId = StatusEffectId(statusId),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Cleanse(
            targets = targets.toDomain(),
            onlyHarmful = onlyHarmful.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Ignite(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Freeze(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Silence(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Knockback(
            targets = targets.toDomain(),
            direction = direction.toDomain(),
            strength = strength.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Knockup(
            targets = targets.toDomain(),
            strength = strength.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SetVelocity(
            targets = targets.toDomain(),
            direction = direction.toDomain(),
            magnitude = magnitude.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Propel(
            targets = targets.toDomain(),
            direction = direction.toDomain(),
            strength = strength.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Teleport(
            targets = targets.toDomain(),
            destination = destination.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Leap(
            targets = targets.toDomain(),
            destination = destination.toDomain(),
            arcHeight = arcHeight.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SummonLightning(
            targets = targets.toDomain(),
            visualOnly = visualOnly.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
        )
    }

    @Serializable
    @SerialName("set_block")
    public data class SetBlock(
        public val targets: TargeterYaml,
        @SerialName("block_key") public val blockKey: String,
        @SerialName("revert_after_ticks") public val revertAfterTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SetBlock(
            targets = targets.toDomain(),
            blockKey = blockKey,
            revertAfterTicks = revertAfterTicks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
        )
    }

    @Serializable
    @SerialName("remove_block")
    public data class RemoveBlock(
        public val targets: TargeterYaml,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.RemoveBlock(
            targets = targets.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
        )
    }

    @Serializable
    @SerialName("explode")
    public data class Explode(
        public val targets: TargeterYaml,
        public val power: NumericValueYaml,
        @SerialName("break_blocks") public val breakBlocks: BoolValueYaml = BoolValueYaml.Literal(false),
        public val incendiary: BoolValueYaml = BoolValueYaml.Literal(false),
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Explode(
            targets = targets.toDomain(),
            power = power.toDomain(),
            breakBlocks = breakBlocks.toDomain(),
            incendiary = incendiary.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.GiveItem(
            targets = targets.toDomain(),
            itemId = ItemId(itemId),
            count = count.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.ConsumeItem(
            targets = targets.toDomain(),
            itemId = ItemId(itemId),
            count = count.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SetCooldown(
            targets = targets.toDomain(),
            skillId = SkillId(skillId),
            cooldownTicks = cooldownTicks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SpawnParticle(
            targets = targets.toDomain(),
            particleId = ParticleId(particleId),
            count = count.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
        )
    }

    @Serializable
    @SerialName("schedule_particle_animation")
    public data class ScheduleParticleAnimation(
        public val targets: TargeterYaml,
        @SerialName("animation_id") public val animationId: String,
        @SerialName("delay_ticks") public val delayTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("period_ticks") public val periodTicks: IntValueYaml = IntValueYaml.Literal(0),
        @SerialName("duration_ticks") public val durationTicks: IntValueYaml = IntValueYaml.Literal(0),
        public val condition: ConditionYaml? = null,
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.ScheduleParticleAnimation(
            targets = targets.toDomain(),
            animationId = ParticleAnimationId(animationId),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SpawnDisplay(
            targets = targets.toDomain(),
            display = display.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Glow(
            targets = targets.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.CameraShake(
            targets = targets.toDomain(),
            intensity = intensity.toDomain(),
            ticks = ticks.toDomain(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
        )
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SendMessage(
            targets = targets.toDomain(),
            message = message.toLocalizedText(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.SendActionBar(
            targets = targets.toDomain(),
            message = message.toLocalizedText(),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.CastSkill(
            targets = targets.toDomain(),
            skillId = SkillId(skillId),
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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
    ) : MechanicYaml() {
        override fun toDomain(): Mechanic = Mechanic.Choose(
            targets = targets.toDomain(),
            weighted = weighted.map { it.toDomain() },
            delayTicks = delayTicks.toDomain(),
            periodTicks = periodTicks.toDomain(),
            durationTicks = durationTicks.toDomain(),
            condition = condition?.toDomain(),
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

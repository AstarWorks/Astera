package com.astarworks.astera.core.domain.skill.mechanic

import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.effect.DisplaySpec
import com.astarworks.astera.core.domain.effect.Audience
import com.astarworks.astera.core.domain.effect.sound.PitchArc
import com.astarworks.astera.core.domain.effect.sound.SoundScale
import com.astarworks.astera.core.domain.effect.vfx.SpriteId
import com.astarworks.astera.core.domain.effect.vfx.telegraph.TelegraphShape
import com.astarworks.astera.core.domain.effect.vfx.telegraph.TelegraphTeam
import com.astarworks.astera.core.domain.effect.vfx.VfxId
import com.astarworks.astera.core.domain.effect.animation.ParticleAnimation
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.core.domain.effect.SoundId
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.LocationRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.BoolValue
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * Declarative skill action.
 *
 * Each `Mechanic` is one composable, side-effect-free instruction the
 * dispatcher executes against the current cast context. Every variant
 * carries:
 *
 *  - [targets] — who / where the effect lands ([Targeter]).
 *  - [delayTicks] / [periodTicks] / [durationTicks] — timing schedule
 *    folded into TaskUnit-style execution (no separate Delay / Tick /
 *    Repeat variants).
 *  - [condition] — optional gate evaluated at dispatch time.
 *
 * The 35 variants are grouped 7 thematic + 4 control-flow:
 *
 *  - Gameplay (3) — Damage / Heal / Resurrect
 *  - Status (6) — ApplyStatus / RemoveStatus / Cleanse / Ignite / Freeze /
 *    Silence
 *  - Movement (5) — Knockback / Knockup / SetVelocity / Propel / Teleport /
 *    Leap (6 with Leap) / DashStrike / HitStop
 *  - World (4) — SummonLightning / Summon / SetBlock / RemoveBlock / Explode
 *    (5 with Explode)
 *  - Item (4) — GiveItem / ConsumeItem / ModifyAttribute / SetCooldown
 *  - Visual (5) — SpawnParticle / ScheduleParticleAnimation / PlaySound /
 *    SpawnDisplay / Glow / CameraShake (6 with CameraShake)
 *  - Messages (3) — SendMessage / SendActionBar / SendTitle
 *  - Control flow (5) — CastSkill / IfThen / Loop / Choose
 *
 * Wave _skills-2 / ADR-0077.
 */
public sealed class Mechanic {

    /** Who / where the mechanic affects. */
    public abstract val targets: Targeter

    /** Ticks of delay before first execution. `0` = immediate. */
    public open val delayTicks: IntValue get() = IntValue.Literal(0)

    /** Ticks between repeated executions while [durationTicks] is non-zero. `0` = one-shot. */
    public open val periodTicks: IntValue get() = IntValue.Literal(0)

    /** Total duration in ticks the mechanic stays active. `0` = single fire. */
    public open val durationTicks: IntValue get() = IntValue.Literal(0)

    /** Optional gate. `null` = always execute. */
    public open val condition: Condition? get() = null

    /**
     * Delayed / repeating executions (`delay_ticks` > 0 or `period_ticks` > 0) re-read the caster's
     * CURRENT position + look at execution time, so geometric targeters (cone / forward_ray /
     * caster_forward / nearby_entities) follow a dash. `true` keeps the cast-time pose instead.
     */
    public open val snapshotPose: Boolean get() = false

    // ───────────────────────── Gameplay (3) ─────────────────────────

    /** Inflict damage from a list of attribute-typed components. */
    public data class Damage(
        override val targets: Targeter,
        val components: List<DamageComponent>,
        val ignoreArmor: BoolValue = BoolValue.Literal(false),
        val ignoreInvulnerability: BoolValue = BoolValue.Literal(false),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init { require(components.isNotEmpty()) { "Mechanic.Damage.components must not be empty" } }
    }

    /** Restore health to [targets]. */
    public data class Heal(
        override val targets: Targeter,
        val amount: NumericValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Revive downed targets at a fraction of max HP. */
    public data class Resurrect(
        override val targets: Targeter,
        val healFraction: NumericValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    // ───────────────────────── Status (6) ─────────────────────────

    /**
     * Apply a status. [effectDurationTicks] is the status's own internal
     * duration (distinct from the parent's scheduling [durationTicks]).
     */
    public data class ApplyStatus(
        override val targets: Targeter,
        val statusId: StatusEffectId,
        val effectDurationTicks: IntValue,
        val chance: NumericValue = NumericValue.Literal(1.0),
        val stackCount: IntValue = IntValue.Literal(1),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Remove [statusId]. [stacks] `null` removes the whole status; `N` consumes N stacks
     * (the status disappears when none are left).
     */
    public data class RemoveStatus(
        override val targets: Targeter,
        val statusId: StatusEffectId,
        val stacks: IntValue? = null,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Strip all (or matching) statuses from [targets]. */
    public data class Cleanse(
        override val targets: Targeter,
        val onlyHarmful: BoolValue = BoolValue.Literal(true),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Set targets on fire for [ticks]. */
    public data class Ignite(
        override val targets: Targeter,
        val ticks: IntValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Freeze (powder-snow style) for [ticks]. */
    public data class Freeze(
        override val targets: Targeter,
        val ticks: IntValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Suppress skill casts for [ticks]. */
    public data class Silence(
        override val targets: Targeter,
        val ticks: IntValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    // ───────────────────────── Movement (6) ─────────────────────────

    /** Horizontal push along [direction] scaled by [strength]. */
    public data class Knockback(
        override val targets: Targeter,
        val direction: DirectionRef,
        val strength: NumericValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Vertical launch. */
    public data class Knockup(
        override val targets: Targeter,
        val strength: NumericValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Overwrite the target's velocity vector outright. */
    public data class SetVelocity(
        override val targets: Targeter,
        val direction: DirectionRef,
        val magnitude: NumericValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Add a velocity impulse along [direction]. */
    public data class Propel(
        override val targets: Targeter,
        val direction: DirectionRef,
        val strength: NumericValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Instantly relocate to [destination]. */
    public data class Teleport(
        override val targets: Targeter,
        val destination: LocationRef,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Lunge the caster [distance] blocks along its look direction over [ticks] ticks (per-tick
     * teleports), sweeping a capsule of [radius]. Each entity touched runs [onHit] with that entity
     * as `target` (once per dash when [hitOnce]). [invulnerable]: the caster takes no damage while
     * dashing. [stopOnBlock]: the dash ends at the first solid block. [followPitch]: dash along the
     * full look vector instead of the level heading. [targets] is ignored - the caster always dashes.
     */
    public data class DashStrike(
        override val targets: Targeter = Targeter.Caster,
        val distance: NumericValue,
        val ticks: IntValue,
        val radius: NumericValue = NumericValue.Literal(1.2),
        val hitOnce: BoolValue = BoolValue.Literal(true),
        val invulnerable: BoolValue = BoolValue.Literal(true),
        val stopOnBlock: BoolValue = BoolValue.Literal(true),
        val followPitch: BoolValue = BoolValue.Literal(false),
        /** Optional approach spacing; clamp travel before the first body in the dash corridor. */
        val stopBeforeTarget: Double? = null,
        /** Direction in caster axes: +Z forward, +X left, +Y up; null preserves the look heading. */
        val localDirection: com.astarworks.astera.core.domain.geometry.Vec3? = null,
        /** Turn horizontal facing toward movement, preserving camera pitch. */
        val faceMovement: Boolean = false,
        val onHit: List<Mechanic> = emptyList(),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init {
            localDirection?.let {
                require(it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSquared() > 1e-12) {
                    "DashStrike.localDirection must be finite and nonzero"
                }
            }
        }
    }

    /**
     * Freeze [targets] in place for [ticks] ticks to sell an impact (zero velocity, no walking, no
     * jumping). [includeCaster] holds the caster too.
     */
    public data class HitStop(
        override val targets: Targeter,
        val ticks: IntValue,
        val includeCaster: BoolValue = BoolValue.Literal(false),
        /** Also freeze the held targets' skin-rig animation clock (a frozen ghost stands in for the live limbs). */
        val freezeAnimation: Boolean = false,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Refund [amount] of [resource] (ammo / energy) to the [targets]' held-item resource pool
     * (default: the caster), clamped at the pool cap - e.g. a melee hit that refunds a bullet.
     */
    public data class RestoreResource(
        val resource: com.astarworks.astera.core.domain.resource.ResourceType,
        val amount: IntValue,
        override val targets: Targeter = Targeter.Caster,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init { require(amount !is IntValue.Literal || amount.value >= 0) { "Mechanic.RestoreResource.amount must be >= 0" } }
    }

    /** Parabolic jump toward [destination] (Phase 6 resolves arc). */
    public data class Leap(
        override val targets: Targeter,
        val destination: LocationRef,
        val arcHeight: NumericValue = NumericValue.Literal(2.0),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    // ───────────────────────── World (5) ─────────────────────────

    /** Cosmetic lightning strike at [targets]. Pair with `Damage` for harm. */
    public data class SummonLightning(
        override val targets: Targeter,
        val visualOnly: BoolValue = BoolValue.Literal(true),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Spawn an entity. `entityTypeKey` is vendor-neutral (`ZOMBIE`, `mythicmobs:imp`, …). */
    public data class Summon(
        override val targets: Targeter,
        val entityTypeKey: String,
        val count: IntValue = IntValue.Literal(1),
        val lifetimeTicks: IntValue = IntValue.Literal(0),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init { require(entityTypeKey.isNotBlank()) { "Mechanic.Summon.entityTypeKey must not be blank" } }
    }

    /**
     * Place a block temporarily. `blockKey` is a vanilla / namespaced block id.
     * Only air / liquid / plant cells are replaced. The block reverts after
     * [revertAfterTicks] (`0` = the runtime default of 200 ticks; there are no
     * permanent edits). Skipped outside a match arena unless [outsideArena].
     */
    public data class SetBlock(
        override val targets: Targeter,
        val blockKey: String,
        val revertAfterTicks: IntValue = IntValue.Literal(0),
        val outsideArena: BoolValue = BoolValue.Literal(false),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init { require(blockKey.isNotBlank()) { "Mechanic.SetBlock.blockKey must not be blank" } }
    }

    /**
     * Remove a block (set to air) temporarily: it is restored after
     * [revertAfterTicks] (`0` = the runtime default of 200 ticks). Never
     * touches indestructible blocks (bedrock, barriers, ...). Skipped outside a
     * match arena unless [outsideArena].
     */
    public data class RemoveBlock(
        override val targets: Targeter,
        val revertAfterTicks: IntValue = IntValue.Literal(0),
        val outsideArena: BoolValue = BoolValue.Literal(false),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Shape of a [BreakArea] cut. */
    public enum class AreaShape { SPHERE, LINE, BOX }

    /** Shape of a [PlaceShape] build. */
    public enum class BuildShape { WALL, BRIDGE, PILLAR, PLATFORM }

    /**
     * Break the world blocks inside [shape] in front of the caster (tool skills: pickaxe quarry, axe cleave, ...).
     * SPHERE: [radius] around the point [length] ahead; LINE: a [length]-long tube of [width] along the look
     * direction; BOX: [width] x [height] x [length] ahead. [power] is the hardness ceiling (1 = soil / wood / wool /
     * glass, 2 = + stone and ores, 3 = anything not protected) and [toolClass] (PICKAXE / AXE / SHOVEL) narrows the
     * cut to what that tool class digs. Edits revert like [RemoveBlock] and only touch arena blocks the edit guard allows.
     */
    public data class BreakArea(
        val shape: AreaShape,
        val radius: Double = 2.0,
        val length: Double = 6.0,
        val width: Double = 3.0,
        val height: Double = 3.0,
        val power: Double = 2.0,
        val toolClass: String? = null,
        val revertAfterTicks: IntValue = IntValue.Literal(0),
        val outsideArena: BoolValue = BoolValue.Literal(false),
        override val targets: Targeter = Targeter.Caster,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init {
            require(radius in 0.0..16.0) { "Mechanic.BreakArea.radius must be in 0..16: $radius" }
            require(length in 0.0..32.0) { "Mechanic.BreakArea.length must be in 0..32: $length" }
            require(width in 1.0..16.0) { "Mechanic.BreakArea.width must be in 1..16: $width" }
            require(height in 1.0..16.0) { "Mechanic.BreakArea.height must be in 1..16: $height" }
            require(power in 0.0..3.0) { "Mechanic.BreakArea.power must be in 0..3: $power" }
        }
    }

    /**
     * Raise a temporary [shape] of [blockKey] in front of the caster, only into empty space: WALL [width] x [height]
     * one block thick just ahead, BRIDGE a [width]-wide path [length] long at the feet level ahead, PILLAR a
     * [height]-tall column just ahead, PLATFORM a [width] x [length] slab at the feet. With [consumeFromInventory]
     * each placed block costs one inventory item ([itemId], default the block key's name); placement stops when
     * the inventory runs out. Reverts like [SetBlock].
     */
    public data class PlaceShape(
        val shape: BuildShape,
        val blockKey: String,
        val width: Int = 3,
        val height: Int = 3,
        val length: Int = 4,
        val consumeFromInventory: Boolean = false,
        val itemId: String? = null,
        val revertAfterTicks: IntValue = IntValue.Literal(0),
        val outsideArena: BoolValue = BoolValue.Literal(false),
        override val targets: Targeter = Targeter.Caster,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init {
            require(blockKey.isNotBlank()) { "Mechanic.PlaceShape.blockKey must not be blank" }
            require(width in 1..16) { "Mechanic.PlaceShape.width must be in 1..16: $width" }
            require(height in 1..16) { "Mechanic.PlaceShape.height must be in 1..16: $height" }
            require(length in 1..32) { "Mechanic.PlaceShape.length must be in 1..32: $length" }
        }
    }

    /**
     * Explosion at each target location. [power] is the visual strength and,
     * unless [radius] is given, the blast radius in blocks. [components] is the
     * damage dealt at the centre (linear falloff to the edge) through the
     * Astera damage pipeline; empty = purely visual. `breakBlocks=false` (the
     * default) never touches terrain; `true` only breaks blocks inside a match
     * arena, through the block-durability system.
     */
    public data class Explode(
        override val targets: Targeter,
        val power: NumericValue,
        val breakBlocks: BoolValue = BoolValue.Literal(false),
        val incendiary: BoolValue = BoolValue.Literal(false),
        val radius: NumericValue? = null,
        val components: List<DamageComponent> = emptyList(),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    // ───────────────────────── Item (4) ─────────────────────────

    public data class GiveItem(
        override val targets: Targeter,
        val itemId: ItemId,
        val count: IntValue = IntValue.Literal(1),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    public data class ConsumeItem(
        override val targets: Targeter,
        val itemId: ItemId,
        val count: IntValue = IntValue.Literal(1),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Adjust a generic attribute (max-health, movement-speed, …) for [ticks]. */
    public data class ModifyAttribute(
        override val targets: Targeter,
        val attributeKey: String,
        val delta: NumericValue,
        val ticks: IntValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init { require(attributeKey.isNotBlank()) { "Mechanic.ModifyAttribute.attributeKey must not be blank" } }
    }

    /** Override the cooldown timer for [skillId]. */
    public data class SetCooldown(
        override val targets: Targeter,
        val skillId: SkillId,
        val cooldownTicks: IntValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    // ───────────────────────── Visual (6) ─────────────────────────

    /** Emit a single-frame particle effect. */
    public data class SpawnParticle(
        override val targets: Targeter,
        val particleId: ParticleId,
        val count: IntValue = IntValue.Literal(1),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Play a time-animated particle pattern (ADR-0196) at each resolved location.
     * Direction is the caster's look direction. Pair with `delay_ticks` /
     * `period_ticks` / `duration_ticks` to repeat it.
     */
    public data class ScheduleParticleAnimation(
        override val targets: Targeter,
        val animation: ParticleAnimation,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Arm one retaliation against the next damaging attack within range and the time window. */
    public data class Counter(
        override val targets: Targeter,
        val windowTicks: IntValue,
        val maxDistance: NumericValue,
        val onHit: List<Mechanic>,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** A timed registered player animation; pose restoration belongs to the gateway. */
    public data class PlayAnimation(
        override val targets: Targeter,
        val animation: com.astarworks.astera.core.domain.animation.player.CustomAnimationId,
        val ticks: IntValue = IntValue.Literal(20),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    public data class PlaySound(
        override val targets: Targeter,
        val soundId: SoundId,
        val volume: NumericValue = NumericValue.Literal(1.0),
        val pitch: NumericValue = NumericValue.Literal(1.0),
        /** Glide the pitch over time (overrides [pitch]); expanded by the sound planner. */
        val pitchArc: PitchArc? = null,
        /** Step the pitch through a musical scale on repeated plays. */
        val scale: SoundScale? = null,
        /** Who hears it; `ALL` = every listener in range. */
        val viewers: Audience = Audience.ALL,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Spawn a Display Entity (block / item / text). */
    public data class SpawnDisplay(
        override val targets: Targeter,
        val display: DisplaySpec,
        val viewers: Audience = Audience.ALL,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Plays a `content/vfx` display choreography (ADR-0220) at each target. Entity targets
     * anchor on that entity (and follow it when [follow] / the definition says so);
     * location targets are fixed. [scale] multiplies every length of the effect.
     */
    public data class PlayVfx(
        override val targets: Targeter,
        val vfxId: VfxId,
        /** Requires the target selected by for_each_target. */
        val trackBoundTarget: Boolean = false,
        val scale: NumericValue = NumericValue.Literal(1.0),
        val follow: BoolValue? = null,
        /** Hit-stop windows (effect-relative ticks) that freeze the rigs with `respect_hit_stop`. */
        val freeze: List<com.astarworks.astera.core.domain.effect.vfx.VfxFreeze> = emptyList(),
        val viewers: Audience = Audience.ALL,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Apply outline glow for [ticks]. */
    public data class Glow(
        override val targets: Targeter,
        val ticks: IntValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Hides the held item from every other viewer for [ticks] (the skill's own weapon rig stands in for it). */
    public data class HideHeldItem(
        override val targets: Targeter,
        val ticks: IntValue,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Camera shake for [ticks] with [intensity] (0..1). */
    public data class CameraShake(
        override val targets: Targeter,
        val intensity: NumericValue,
        val ticks: IntValue,
        val shape: ShakeShape = ShakeShape.DECAY,
        val direction: ShakeDirection = ShakeDirection.NONE,
        /** Oscillations per second; `0` = the profile's default. */
        val frequency: NumericValue = NumericValue.Literal(0.0),
        val viewers: Audience = Audience.ALL,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Cut-in screen flash: a full-screen [overlay] glyph shown as a title (so it replaces any
     * gameplay title on the viewer for its few ticks; text belongs in the subtitle / action bar).
     * [colorRgb] (0xRRGGBB) tints it, `null` = the overlay's default. Viewers are [targets].
     */
    public data class ScreenFlash(
        override val targets: Targeter,
        val overlay: ScreenOverlay,
        val colorRgb: Int? = null,
        val fadeInTicks: IntValue = IntValue.Literal(0),
        val stayTicks: IntValue = IntValue.Literal(1),
        val fadeOutTicks: IntValue = IntValue.Literal(2),
        val viewers: Audience = Audience.ALL,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Stops the sound [soundId] for the [targets] (cuts a wind-up cue at the impact). */
    public data class StopSound(
        override val targets: Targeter,
        val soundId: SoundId,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Camera-anchored sprite veil (ADR-0291): a display [distance] blocks in front of each viewer's camera, [scale] times
     * its natural size, for [ticks] ticks. Covers at most [maxScreenFraction] of the view. Viewers are [targets] filtered by
     * [viewers] (default caster only).
     */
    public data class ScreenVeil(
        override val targets: Targeter,
        val sprite: SpriteId,
        val distance: NumericValue = NumericValue.Literal(0.6),
        val scale: NumericValue = NumericValue.Literal(1.0),
        val ticks: IntValue,
        val colorRgb: Int? = null,
        val viewers: Audience = Audience.CASTER,
        val maxScreenFraction: NumericValue = NumericValue.Literal(0.15),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Ground warning marker at each location of [targets]: the full footprint on tick 0, an inner fill growing until
     * [impactTick] (default [ticks]). [teamColor] picks the warning colour (enemy) or the friendly team colour (ally).
     */
    public data class Telegraph(
        override val targets: Targeter,
        val shape: TelegraphShape = TelegraphShape.CIRCLE,
        val radius: NumericValue,
        /** Cone full angle in degrees (shape `cone`). */
        val angleDegrees: NumericValue = NumericValue.Literal(60.0),
        /** Line width in blocks (shape `line`; its length is [radius]). */
        val width: NumericValue = NumericValue.Literal(2.0),
        val ticks: IntValue,
        val impactTick: IntValue? = null,
        val teamColor: TelegraphTeam = TelegraphTeam.ENEMY,
        val viewers: Audience = Audience.ALL,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    // ───────────────────────── Messages (3) ─────────────────────────

    public data class SendMessage(
        override val targets: Targeter,
        val message: LocalizedText,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    public data class SendActionBar(
        override val targets: Targeter,
        val message: LocalizedText,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    public data class SendTitle(
        override val targets: Targeter,
        val title: LocalizedText,
        val subtitle: LocalizedText? = null,
        val fadeInTicks: IntValue = IntValue.Literal(10),
        val stayTicks: IntValue = IntValue.Literal(40),
        val fadeOutTicks: IntValue = IntValue.Literal(10),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    // ───────────────────────── Control flow (5) ─────────────────────────

    /**
     * Resolve once, then keep each entity as Target in delayed/repeated children. [ifEmpty] runs once, in the
     * unchanged context, when no living target was selected (ADR-0286).
     */
    public data class ForEachTarget(
        override val targets: Targeter,
        val mechanics: List<Mechanic>,
        val ifEmpty: List<Mechanic> = emptyList(),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /**
     * Cast another skill from inside this mechanic chain. Cycle detection
     * runs at registry load time via `SkillCycleValidator`.
     */
    public data class CastSkill(
        override val targets: Targeter,
        val skillId: SkillId,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Branch on a [Condition]. `else_` is optional. */
    public data class IfThen(
        override val targets: Targeter,
        val predicate: Condition,
        val then_: List<Mechanic>,
        val else_: List<Mechanic> = emptyList(),
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic()

    /** Run [mechanics] [count] times with [strideTicks] between iterations. */
    public data class Loop(
        override val targets: Targeter,
        val count: IntValue,
        val strideTicks: IntValue = IntValue.Literal(0),
        val mechanics: List<Mechanic>,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init { require(mechanics.isNotEmpty()) { "Mechanic.Loop.mechanics must not be empty" } }
    }

    /** Weighted random pick of one branch. Weights need not sum to 1. */
    public data class Choose(
        override val targets: Targeter,
        val weighted: List<WeightedBranch>,
        override val delayTicks: IntValue = IntValue.Literal(0),
        override val periodTicks: IntValue = IntValue.Literal(0),
        override val durationTicks: IntValue = IntValue.Literal(0),
        override val condition: Condition? = null,
        override val snapshotPose: Boolean = false,
    ) : Mechanic() {
        init {
            require(weighted.isNotEmpty()) { "Mechanic.Choose.weighted must not be empty" }
            require(weighted.all { it.weight > 0.0 }) { "Mechanic.Choose weights must be > 0" }
        }
    }

    /**
     * One branch of a [Choose]. [weight] is a raw scalar; the dispatcher
     * normalizes against the sum.
     */
    public data class WeightedBranch(
        public val weight: Double,
        public val mechanics: List<Mechanic>,
    ) {
        init {
            require(weight > 0.0) { "WeightedBranch.weight must be > 0: $weight" }
            require(mechanics.isNotEmpty()) { "WeightedBranch.mechanics must not be empty" }
        }
    }
}

/** Camera-shake envelope: `DECAY` rings out smoothly, `IMPULSE` is one sharp kick that settles. */
public enum class ShakeShape(public val yamlName: String) {
    DECAY("decay"),
    IMPULSE("impulse"),
    ;

    public companion object {
        public fun fromYaml(raw: String): ShakeShape =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown shake shape '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}

/** Direction of a camera shake: `NONE` = random jitter, `FROM_HIT` = kicked away from the hit source. */
public enum class ShakeDirection(public val yamlName: String) {
    NONE("none"),
    FROM_HIT("from_hit"),
    ;

    public companion object {
        public fun fromYaml(raw: String): ShakeDirection =
            entries.firstOrNull { it.yamlName.equals(raw.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown shake direction '$raw' (expected: ${entries.joinToString("/") { it.yamlName }})")
    }
}

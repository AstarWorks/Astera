package com.astarworks.astera.core.domain.skillengine.table

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.effect.ParticleData
import java.util.UUID

/** One gateway call or table transition (ADR-0303 11.3 vocabulary). */
public sealed interface Action {
    // presentation
    public data class Spawn(val kind: SpawnKind, val anchor: InstanceId?, val anchorName: String, val pose: Pose, val look: Look) : Action
    public data class Key(val display: InstanceId, val durationTicks: Int, val translation: Vec, val leftRotation: Rot, val scale: Vec) : Action
    public data class Teleport(val display: InstanceId, val durationTicks: Int, val position: Vec, val yaw: Num, val pitch: Num) : Action
    public data class TrailPoint(val ribbon: InstanceId, val position: Vec) : Action
    public data class Particle(val type: String, val count: Val<Int>, val offset: Vec, val extra: Num, val data: ParticleData?, val position: Vec, val sweep: Double?) : Action

    /** A custom particle definition (`op: particle_def, def: <id>`), played with the same timing block as a vanilla particle. */
    public data class ParticleDef(val def: String, val position: Vec, val count: Val<Int>? = null) : Action
    public data class Sound(val sound: String, val volume: Num, val pitch: Num, val position: Vec, val category: String) : Action

    /** One per-tick write of a non-transform field ([field] e.g. `text_opacity`, `count`, `volume`) of the row's instance (`fade`, 3.4). */
    public data class Set(val field: String, val value: Val<Any>) : Action

    // gameplay
    /**
     * Detection + damage in one op (ADR-0303 10.1b, R7/R21/R24). [anchor] is the frame the shape's points are in; [targets] filters the victims the shape found,
     * nearest first. [damage] is applied to each victim (each amount read once per victim, before any victim is damaged); empty = detection only, no damage call.
     * [finalDetection] marks the op's last detection tick: a hit that found nobody on any tick of the op raises `<op>.miss` there.
     */
    public data class Hit(
        val shape: HitShape,
        val anchor: InstanceId?,
        val anchorName: String,
        val targets: HitTargets,
        val window: HitWindow,
        val ignoreInvulnerability: Boolean,
        val finalDetection: Boolean,
        val damage: List<HitDamage>,
    ) : Action

    public data class Damage(val targets: TargetRule, val components: List<DamageComponent>, val ignoreInvulnerability: Boolean) : Action
    public data class Status(val targets: TargetRule, val status: String, val stacks: Val<Int>, val forTicks: Int) : Action

    /** impulse, knockback, heal, resource, modify_attribute, dash, reel, rig. */
    public data class Effect(val op: OpId, val payload: Map<String, Val<Any>>) : Action

    /**
     * A world edit or world-scale effect (`break_area`, `place_shape`, `explode`, `lightning`). [origin] is where it
     * happens, [forward] the look direction shapes face; [payload] holds the op's flat fields.
     */
    public data class World(val op: OpId, val origin: Vec, val forward: Vec, val payload: Map<String, Val<Any>>) : Action
    public data class Lock(val move: LockMove, val pitch: Boolean, val yaw: Boolean, val until: LockEnd) : Action

    // structure
    public data class Call(val callee: Callee, val callId: InstanceId, val with: Map<String, Val<Any>>, val frames: Map<String, InstanceId>) : Action
    public data class Emit(val event: EventPath) : Action
    public data class End(val instance: InstanceId) : Action
    public data class Cancel(val scope: CancelScope) : Action

    /** Machine bookkeeping rows (printed). */
    public data class Enter(val state: StatePath) : Action
    public data class Exit(val state: StatePath, val reason: ExitReason) : Action
}

public enum class ExitReason { TIMER, EVENT, PREEMPT, CANCEL }

/** Same-tick sort order (ADR-0303 10.2a): rows sort by (tick, phase, declaration order, occurrence). */
public enum class Phase { BOOKKEEPING, SPAWN, WRITE, PRESENTATION, GAMEPLAY, LOCK, CALL, EMIT, END }

public val Action.phase: Phase
    get() = when (this) {
        is Action.Enter, is Action.Exit -> Phase.BOOKKEEPING
        is Action.Spawn -> Phase.SPAWN
        is Action.Key, is Action.Teleport, is Action.TrailPoint -> Phase.WRITE
        is Action.Particle, is Action.ParticleDef, is Action.Sound, is Action.Set -> Phase.PRESENTATION
        is Action.Hit, is Action.Damage, is Action.Status, is Action.Effect, is Action.World -> Phase.GAMEPLAY
        is Action.Lock -> Phase.LOCK
        is Action.Call -> Phase.CALL
        is Action.Emit, is Action.Cancel -> Phase.EMIT
        is Action.End -> Phase.END
    }

public enum class SpawnKind { FRAME, ITEM, BLOCK, TEXT, SPRITE, RIBBON, PROJECTILE_BODY }

public sealed interface Callee {
    public data class PartRef(val id: String) : Callee
    public data class SkillRef(val id: String, val gateCooldown: Boolean, val gateCost: Boolean) : Callee
}

public sealed interface CancelScope {
    public data object Cast : CancelScope
    public data object State : CancelScope
    public data class Instance(val id: InstanceId) : CancelScope
}

public data class Pose(val position: Vec, val rotation: Rot, val scale: Vec)

/** item model, block, glyph, billboard, brightness, text... 1:1 vanilla. */
public data class Look(val fields: Map<String, Val<Any>>)

/** Volume of a `hit` row. */
public sealed interface HitShape {
    public data class Sphere(val center: Vec, val radius: Num) : HitShape
    public data class Capsule(val from: Vec, val to: Vec, val radius: Num) : HitShape
    public data class Box(val center: Vec, val halfExtents: Vec, val rotation: Rot) : HitShape

    /**
     * Sphere wedge: within [range] of [origin], horizontal angle to [forward] at most `deg / 2` (`deg` is the full opening), within +-[height] / 2 of the origin vertically.
     * [flat] = `forward: yaw` (R12): the player flattens [forward] to the horizontal and normalises it; false = `forward: look`, used as is.
     */
    public data class Sector(val origin: Vec, val forward: Vec, val deg: Num, val range: Num, val height: Num, val flat: Boolean) : HitShape
}

/** Who a gameplay row touches; resolved at play time (C4c). */
public sealed interface TargetRule {
    /** `caster`, `event.victim`, `event.attacker`. */
    public data class Entity(val ref: String) : TargetRule

    /** `{kind: enemies|allies, around: <frame>, radius, max_count, include_self}`; every field is required. */
    public data class Area(
        val kind: String,
        val around: InstanceId?,
        val aroundName: String,
        val radius: Num,
        /** Keep the nearest N to the centre; null = unlimited (R8). */
        val maxCount: Int?,
        val includeSelf: Boolean,
    ) : TargetRule
}

/** Which of the victims a `hit` shape found are kept: [kind] `enemies` | `allies` | `all` (never the caster), nearest-first from the shape origin, at most [maxCount] (null = all). */
public data class HitTargets(val kind: String, val maxCount: Int?)

/** One damage band of a `hit`; [amount] may read `event.victim`, `contact.*` and `stacks(...)`, so it is evaluated per victim. */
public data class HitDamage(val attribute: DamageAttribute, val amount: Num)

/** Re-hit rule of a repeating `hit`: once per victim per instance, or again after [Every.ticks]. */
public sealed interface HitWindow {
    public data object Once : HitWindow
    public data class Every(val ticks: Int) : HitWindow
}

/** What a `lock` row takes from the caster's feet (R32): nothing, walking only (a jump still works), or walking and jumping. */
public enum class LockMove { NONE, WALK, ALL }

/** When a `lock` row lifts, in ticks relative to the state base (entry = 0). */
public sealed interface LockEnd {
    public data class AtTick(val tick: Int) : LockEnd
    public data object CastEnd : LockEnd
}

/** A resolved victim set (a `Val.Known` only for fixed sets such as the caster). */
public data class TargetSet(val entities: List<EntityRef>)

@JvmInline
public value class EntityRef(public val id: UUID)

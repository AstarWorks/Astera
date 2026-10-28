package com.astarworks.astera.core.domain.skillengine.table

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
    public data class Particle(val type: String, val count: Val<Int>, val offset: Vec, val extra: Num, val data: ParticleData?, val position: Vec) : Action
    public data class Sound(val sound: String, val volume: Num, val pitch: Num, val position: Vec, val category: String) : Action

    // gameplay
    public data class Hit(val shape: HitShape, val frames: List<InstanceId>, val targets: TargetRule, val once: Boolean) : Action
    public data class Damage(val targets: Val<TargetSet>, val components: List<DamageComponent>, val chance: Num) : Action
    public data class Status(val targets: Val<TargetSet>, val status: String, val stacks: Val<Int>, val forTicks: Int) : Action

    /** impulse, knockback, heal, resource, modify_attribute, dash, reel, rig. */
    public data class Effect(val op: OpId, val payload: Map<String, Val<Any>>) : Action
    public data class Lock(val move: Boolean, val pitch: Boolean, val yaw: Boolean, val untilTick: Int) : Action

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
        is Action.Particle, is Action.Sound -> Phase.PRESENTATION
        is Action.Hit, is Action.Damage, is Action.Status, is Action.Effect -> Phase.GAMEPLAY
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
}

/** Who a `hit` row may touch; resolved at play time (C4c). */
public sealed interface TargetRule {
    public data object Victim : TargetRule
    public data object Caster : TargetRule
    public data class Enemies(val around: Vec, val radius: Num, val maxCount: Int?) : TargetRule
    public data class Allies(val around: Vec, val radius: Num, val maxCount: Int?, val includeSelf: Boolean) : TargetRule
}

/** A resolved victim set (a `Val.Known` only for fixed sets such as the caster). */
public data class TargetSet(val entities: List<EntityRef>)

@JvmInline
public value class EntityRef(public val id: UUID)

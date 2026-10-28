package com.astarworks.astera.core.domain.skillengine.frame

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame

/** Frame kinds (design 3.1). Built-ins: world, caster, enemy (victim root), contact. */
public enum class FrameKind { WORLD, CASTER, BONE, ITEM, PART, BODY, ENEMY, CONTACT, POINT }

@JvmInline
public value class FrameId(public val value: String)

/**
 * A node's pose is [local], expressed in [parent]. The root ("world") has no parent. With an [orient] the world rotation is
 * chosen by it (live, every read) instead of inherited, and [local]'s rotation is applied on top.
 */
public data class FrameNode(
    val id: FrameId,
    val kind: FrameKind,
    val parent: FrameId?,
    val local: Frame,
    val orient: FrameOrient? = null,
    /** Uniform scale this node applies to its descendants (their local translations and display sizes), like the v1 `play_vfx scale`. */
    val scale: Double = 1.0,
    /** Body frame hung on the caster: true = its displays ride the caster as native passengers (v1 `attach_to_anchor`, ADR-0276), false = they follow by teleport. Null on every other node. */
    val passenger: Boolean? = null,
    /** A non-passenger caster body frame of a followed vfx: its displays are written with this `teleport_duration` (v1 `follow_teleport_ticks` or 2, VfxPlayer.kt:416-419). */
    val teleportTicks: Int? = null,
    /** Same, for a display a track moves itself; only an explicit v1 `follow_teleport_ticks` sets it (VfxPlayer.kt:442). */
    val movingTeleportTicks: Int? = null,
)

/** A named attachment point on a node: `<node>.<name>` resolves to `node.world.toWorld(offset)`. */
public data class FramePoint(val node: FrameId, val name: String, val offset: Vec3, val axis: Vec3? = null)

public sealed interface FrameError {
    public data class Unknown(val id: String) : FrameError
    public data class Duplicate(val id: String) : FrameError
    public data class Cycle(val id: String, val newParent: String) : FrameError
    public data class UnknownPoint(val ref: String) : FrameError
}

/**
 * The single coordinate system of a cast (ADR-0301). One tree per cast; mutated only by motion laws
 * and reparent ops, read by hit volumes and presentation ops.
 */
public interface FrameTree {
    public fun node(id: FrameId): FrameNode?
    public fun children(id: FrameId): List<FrameId>

    public fun add(node: FrameNode): Result<Unit, FrameError>
    public fun remove(id: FrameId)

    /** Replace the local pose of [id] (parent unchanged). */
    public fun setLocal(id: FrameId, local: Frame): Result<Unit, FrameError>

    /** Product of the [FrameNode.scale] of [id] and every ancestor: what a display parented under [id] is multiplied by. */
    public fun scaleOf(id: FrameId): Double {
        var k = 1.0
        var cur: FrameId? = id
        while (cur != null) {
            val n = node(cur) ?: break
            k *= n.scale
            cur = n.parent
        }
        return k
    }

    /** World pose of [id] (composition of locals up to the root; a parent's scale multiplies the child's local translation). */
    public fun worldOf(id: FrameId): Result<Frame, FrameError>

    /**
     * Move [id] under [newParent]. With [keepWorld] the local pose is recomputed so the world pose is
     * unchanged (no position jump). [snap], if given, is the new local pose and wins over [keepWorld].
     */
    public fun reparent(
        id: FrameId,
        newParent: FrameId,
        keepWorld: Boolean = true,
        snap: Frame? = null,
    ): Result<Unit, FrameError>

    /** Register / replace a named point. */
    public fun definePoint(point: FramePoint)

    public fun point(node: FrameId, name: String): FramePoint?

    /** World position of `node.name` (a missing point name means the node origin). */
    public fun pointWorld(node: FrameId, name: String?): Result<Vec3, FrameError>
}

package com.astarworks.astera.core.domain.skillengine.frame

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame

/** Frame kinds (design 3.1). Built-ins: world, caster, enemy (victim root), contact. */
public enum class FrameKind { WORLD, CASTER, BONE, ITEM, PART, BODY, ENEMY, CONTACT, POINT }

@JvmInline
public value class FrameId(public val value: String)

/** A node's pose is [local], expressed in [parent]. The root ("world") has no parent. */
public data class FrameNode(
    val id: FrameId,
    val kind: FrameKind,
    val parent: FrameId?,
    val local: Frame,
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

    /** World pose of [id] (composition of locals up to the root). */
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

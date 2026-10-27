package com.astarworks.astera.core.domain.skillengine.frame

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame

/** Pose of [world] expressed in [parent] (`parent.inverse * world`). */
public fun relativeTo(parent: Frame, world: Frame): Frame {
    val inv = parent.rotation.conjugate()
    return Frame(inv.rotate(world.origin - parent.origin), (inv * world.rotation).normalized())
}

/**
 * Single-threaded, per-cast [FrameTree]. Nodes live in a map; world poses are composed on demand.
 * Removing a node removes its whole subtree (and the points defined on it).
 */
public class InMemoryFrameTree : FrameTree {
    private val nodes = LinkedHashMap<FrameId, FrameNode>()
    private val kids = HashMap<FrameId, MutableList<FrameId>>()
    private val points = HashMap<Pair<FrameId, String>, FramePoint>()

    override fun node(id: FrameId): FrameNode? = nodes[id]

    override fun children(id: FrameId): List<FrameId> = kids[id]?.toList() ?: emptyList()

    override fun add(node: FrameNode): Result<Unit, FrameError> {
        if (nodes.containsKey(node.id)) return Result.failure(FrameError.Duplicate(node.id.value))
        val parent = node.parent
        if (parent != null && !nodes.containsKey(parent)) return Result.failure(FrameError.Unknown(parent.value))
        nodes[node.id] = node
        if (parent != null) kids.getOrPut(parent) { mutableListOf() }.add(node.id)
        return Result.success(Unit)
    }

    override fun remove(id: FrameId) {
        val node = nodes[id] ?: return
        for (child in children(id)) remove(child)
        node.parent?.let { kids[it]?.remove(id) }
        nodes.remove(id)
        kids.remove(id)
        points.keys.removeAll { it.first == id }
    }

    override fun setLocal(id: FrameId, local: Frame): Result<Unit, FrameError> {
        val node = nodes[id] ?: return Result.failure(FrameError.Unknown(id.value))
        nodes[id] = node.copy(local = local)
        return Result.success(Unit)
    }

    override fun worldOf(id: FrameId): Result<Frame, FrameError> {
        var node = nodes[id] ?: return Result.failure(FrameError.Unknown(id.value))
        var world = node.local
        while (true) {
            val parentId = node.parent ?: return Result.success(world)
            node = nodes[parentId] ?: return Result.failure(FrameError.Unknown(parentId.value))
            world = node.local.compose(world)
        }
    }

    override fun reparent(id: FrameId, newParent: FrameId, keepWorld: Boolean, snap: Frame?): Result<Unit, FrameError> {
        val node = nodes[id] ?: return Result.failure(FrameError.Unknown(id.value))
        if (!nodes.containsKey(newParent)) return Result.failure(FrameError.Unknown(newParent.value))
        if (isSelfOrDescendant(newParent, id)) return Result.failure(FrameError.Cycle(id.value, newParent.value))
        val newLocal = when {
            snap != null -> snap
            keepWorld -> {
                val world = worldOf(id).getOrNull() ?: return Result.failure(FrameError.Unknown(id.value))
                val parentWorld = worldOf(newParent).getOrNull() ?: return Result.failure(FrameError.Unknown(newParent.value))
                relativeTo(parentWorld, world)
            }
            else -> node.local
        }
        node.parent?.let { kids[it]?.remove(id) }
        nodes[id] = node.copy(parent = newParent, local = newLocal)
        kids.getOrPut(newParent) { mutableListOf() }.add(id)
        return Result.success(Unit)
    }

    override fun definePoint(point: FramePoint) {
        points[point.node to point.name] = point
    }

    override fun point(node: FrameId, name: String): FramePoint? = points[node to name]

    override fun pointWorld(node: FrameId, name: String?): Result<Vec3, FrameError> {
        val world = worldOf(node).let { r -> r.getOrNull() ?: return Result.failure(r.errorOrNull()!!) }
        if (name == null) return Result.success(world.origin)
        val p = points[node to name] ?: return Result.failure(FrameError.UnknownPoint("${node.value}.$name"))
        return Result.success(world.toWorld(p.offset))
    }

    /** True when [candidate] is [ancestor] itself or lies below it. */
    private fun isSelfOrDescendant(candidate: FrameId, ancestor: FrameId): Boolean {
        var cur: FrameId? = candidate
        while (cur != null) {
            if (cur == ancestor) return true
            cur = nodes[cur]?.parent
        }
        return false
    }
}

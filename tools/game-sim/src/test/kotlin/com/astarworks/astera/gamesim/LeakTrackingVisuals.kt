package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillVisualGateway
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.op.PlateFace
import java.util.UUID

/**
 * Records every spawn / remove pair of the visual port (SKL-14 despawn): [live] is what a real client would still see.
 * An update to a handle that is no longer live, or a second remove, is recorded in [stale].
 */
class LeakTrackingVisuals(private val clock: () -> Int = { 0 }) : ISkillVisualGateway {
    private var ids = 0L
    val live = LinkedHashMap<Long, String>()
    val removedAt = HashMap<Long, Int>()
    val lastOrigin = HashMap<Long, Vec3>()
    val kind = HashMap<Long, String>()
    var spawned = 0
        private set
    val stale = ArrayList<String>()

    private fun open(what: String): VisualHandle {
        val id = ++ids
        live[id] = what
        kind[id] = what
        spawned++
        return VisualHandle(id)
    }

    private fun touch(handle: VisualHandle, what: String) {
        if (handle.id !in live) stale += "$what on removed handle ${handle.id}"
    }

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, scale: Double, pose: Frame) = open("mesh $item/$part")
    override fun spawnPlate(castOwner: UUID, sprite: String, face: PlateFace, size: Double, alpha: Double, pose: Frame) = open("plate $sprite")
    override fun spawnLine(castOwner: UUID, from: Vec3, to: Vec3, thickness: Double, colorHex: String, face: PlateFace) = open("line")
    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = open("trail")
    override fun updatePose(handle: VisualHandle, pose: Frame, interpolationTicks: Int) { touch(handle, "updatePose"); lastOrigin[handle.id] = pose.origin }
    override fun updateScale(handle: VisualHandle, scale: Vec3) = touch(handle, "updateScale")
    override fun updateLine(handle: VisualHandle, from: Vec3, to: Vec3) = touch(handle, "updateLine")
    override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) = touch(handle, "pushTrailPoint")
    override fun remove(handle: VisualHandle) {
        removedAt[handle.id] = clock()
        if (live.remove(handle.id) == null) stale += "remove of unknown or already removed handle ${handle.id}"
    }
}

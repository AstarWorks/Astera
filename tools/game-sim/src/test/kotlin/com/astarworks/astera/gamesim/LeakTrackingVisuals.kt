package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.skillengine.runtime.port.DisplayView
import com.astarworks.astera.core.application.skillengine.runtime.port.VisualPose
import com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo
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
    override fun sprite(sprite: String): com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo = com.astarworks.astera.core.application.skillengine.runtime.port.SpriteInfo(1, 32)
    override fun setPlateFrame(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, frame: Int, tintHex: String?) {}
    override fun setTrailLook(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, fadePower: Double, style: String) {}
    override fun setTrailShape(handle: com.astarworks.astera.core.application.skillengine.runtime.port.VisualHandle, bands: List<com.astarworks.astera.core.domain.skillengine.op.TrailBand>, edge: com.astarworks.astera.core.domain.skillengine.op.TrailEdge?, rim: com.astarworks.astera.core.domain.skillengine.op.TrailRim?, stepDeg: Double, doubleSided: Boolean) {}
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

    override fun spawnMesh(castOwner: UUID, item: String, part: String?, pose: VisualPose, brightness: Int?, look: com.astarworks.astera.core.application.skillengine.runtime.port.MeshLook, view: DisplayView) = open("mesh $item/$part")
    override fun spawnPlate(castOwner: UUID, sprite: String, alpha: Double, tintHex: String?, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) = open("plate $sprite")
    override fun spawnBlock(castOwner: UUID, block: String, pose: VisualPose, brightness: Int?, rigShading: Boolean, view: DisplayView) = open("block")
    override fun spawnQuad(castOwner: UUID, colorHex: String, alpha: Double, text: String?, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) = open("quad")
    override fun spawnText(castOwner: UUID, text: String, backgroundHex: String, backgroundAlpha: Double, shadow: Boolean, seeThrough: Boolean, pose: VisualPose, brightness: Int?, view: DisplayView) = open("text")
    override fun spawnSkinBone(castOwner: UUID, bone: com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone, slim: Boolean, ghost: Boolean, pose: VisualPose) = open("skin_bone ${bone.yamlName}")
    override fun spawnLine(castOwner: UUID, colorHex: String, pose: VisualPose, view: DisplayView) = open("line")
    override fun spawnTrail(castOwner: UUID, ticks: Int, widthFrac: Double, colorHex: String, alpha: Double) = open("trail")
    override fun write(handle: VisualHandle, pose: VisualPose, interpolationTicks: Int) { touch(handle, "write"); lastOrigin[handle.id] = pose.frame.origin }
    override fun pushTrailPoint(handle: VisualHandle, root: Vec3, tip: Vec3) = touch(handle, "pushTrailPoint")
    override fun remove(handle: VisualHandle) {
        removedAt[handle.id] = clock()
        if (live.remove(handle.id) == null) stale += "remove of unknown or already removed handle ${handle.id}"
    }
}

package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.port.outbound.IDisplayEntityGateway.DisplayWrite
import com.astarworks.astera.core.domain.effect.DisplayBillboard
import com.astarworks.astera.core.domain.effect.DisplayTransform
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.op.PlateFace

/**
 * The one display write a skill visual makes per tick (ADR-0301): location, the full transformation, billboard and a
 * single interpolation length for position, rotation and scale alike. Pure maths shared by [PaperSkillVisualGateway]
 * and the game-sim recording gateway, so the sim sees exactly the fields the client receives.
 */
public object SkillDisplayWrites {

    /** Item model: translation 0, rotation and scale as given, entity yaw / pitch unused. */
    public fun mesh(pose: Frame, scale: Vec3, ticks: Int, anchor: DisplayWrite.Anchor?): DisplayWrite = DisplayWrite(
        at = pose.origin, yaw = 0f, pitch = 0f,
        transform = DisplayTransform(Vec3.ZERO, pose.rotation, scale),
        billboard = DisplayBillboard.FIXED,
        interpolationDelay = 0, interpolationDuration = ticks, teleportDuration = ticks,
        anchor = anchor,
    )

    /**
     * Text-quad plate of [width] x [height] blocks. A viewer face is a vanilla billboard (every viewer sees it right) and
     * is never anchored: a billboard turns the whole local frame, so a passenger offset would swing with the camera.
     */
    public fun plate(pose: Frame, face: PlateFace, width: Double, height: Double, ticks: Int, anchor: DisplayWrite.Anchor?): DisplayWrite {
        val placed = PlateOrientation.forPlate(pose, face)
        return DisplayWrite(
            at = pose.origin, yaw = 0f, pitch = 0f,
            transform = SkillDisplayMath.plateTransform(placed.rotation, width, height),
            billboard = placed.billboard,
            interpolationDelay = 0, interpolationDuration = ticks, teleportDuration = ticks,
            anchor = anchor.takeIf { placed.billboard == DisplayBillboard.FIXED },
        )
    }

    /** Stretched line between two points, world-teleported (a line spans two frames, so it has no single anchor). */
    public fun line(from: Vec3, to: Vec3, thickness: Double, face: PlateFace, viewerEye: Vec3?, ticks: Int): DisplayWrite = DisplayWrite(
        at = (from + to) * 0.5, yaw = 0f, pitch = 0f,
        transform = SkillDisplayMath.lineTransform(from, to, thickness, face, viewerEye),
        billboard = DisplayBillboard.FIXED,
        interpolationDelay = 0, interpolationDuration = ticks, teleportDuration = ticks,
        anchor = null,
    )
}

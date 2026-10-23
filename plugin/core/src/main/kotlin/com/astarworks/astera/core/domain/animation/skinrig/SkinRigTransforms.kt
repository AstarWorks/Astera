package com.astarworks.astera.core.domain.animation.skinrig

import com.astarworks.astera.core.domain.geometry.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d

/** Unit quaternion, `(x, y, z, w)`. */
public data class RigQuat(public val x: Double, public val y: Double, public val z: Double, public val w: Double)

/**
 * What one Display entity needs: `translation` (blocks, from the entity position), `rotation`
 * (left rotation) and `scale` — the unit 8 px skull cube is scaled to the bone's box.
 */
public data class RigPartTransform(
    public val translation: Vec3,
    public val rotation: RigQuat,
    public val scale: Vec3,
)

/** All Display transforms of one frame: one per bone plus the held-item display riding the right arm. */
public data class RigTransforms(
    public val bones: Map<SkinRigBone, RigPartTransform>,
    public val heldItem: RigPartTransform,
)

/**
 * Pose to Display transforms (ADR-0250). Pure math; the Paper adapter only copies the numbers.
 *
 * Conventions (the YAML contract):
 *  - Frame: the rig frame of [SkinRigBone] (right-handed, +Y up, front +Z, the player's right hand at -X),
 *    rotated to the player's body yaw by the Display entity's own yaw.
 *  - Rotation: degrees, `R = Rz * Ry * Rx` (X first), counter-clockwise looking down the axis toward the origin,
 *    around the bone pivot. So a negative X rotation on an arm swings it forward and up.
 *  - Position: pixels in the rig frame, added after the rotation; scale: multiplier around the pivot.
 *  - Root: same shape, rotating / translating the whole rig around [ROOT_PIVOT] (the hip).
 */
public object SkinRigTransforms {

    /** Rotation / scale pivot of the `root` channel: the hip, 12 px above the floor. */
    public val ROOT_PIVOT: Vec3 = Vec3(0.0, 12.0, 0.0)

    private const val PX = 1.0 / 16.0

    /** The unit skull cube is 8 px wide. */
    private const val CUBE_PX = 8.0

    /**
     * Vanilla renders every player at 15/16 (`AvatarRenderer.scale`, about the feet); the whole rig and the
     * held item carry the same factor so a rig at rest coincides with the real body.
     */
    public const val PLAYER_SCALE: Double = 0.9375

    /**
     * Held-item anchor relative to the right arm joint, in px in the frame after [HELD_TILT].
     * Derivation: vanilla `ItemInHandLayer` does `rotX(-90) rotY(180) translate(1, 2, -10)/16` in the y-down model
     * space; the rig frame is that space turned by `rotX(180)` (renderer flip x entity yaw), and `ItemDisplay`
     * itself adds a `rotY(180)` before drawing. Folding both gives a `rotX(90)` tilt and the offset `(-1, 2, 10)`:
     * 10 px below the joint, 2 px in front, centred on the arm (x -6).
     */
    private val HAND_OFFSET = Vector3d(-1.0, 2.0, 10.0)

    /** Tilt of the held-item frame relative to the arm, degrees about X; see [HAND_OFFSET]. */
    private const val HELD_TILT = 90.0

    /**
     * Vanilla `poseRightArm` for a held item: `xRot = xRot * 0.5 - PI/10`, i.e. the item arm is lifted 18 degrees
     * forward. The skin arm follows the animation, but the item keeps this wrist pitch so the held blade stands
     * like the vanilla one at rest (negative X swings forward and up).
     */
    private const val HELD_ARM_PITCH = -18.0

    /**
     * @param headLook live head look as Euler degrees `(pitch about X, yaw about Y, 0)`, applied to the head bone
     * as an outer rotation on top of the animation, so the head keeps following the camera like the vanilla head.
     */
    public fun compute(frame: RigFrame, slim: Boolean, headLook: Vec3 = Vec3.ZERO): RigTransforms {
        val rootRot = euler(frame.root.rotation)
        val rootPos = vec(frame.root.position)
        val pivot = vec(ROOT_PIVOT)

        fun toWorld(localPx: Vector3d): Vector3d =
            Vector3d(localPx).sub(pivot).rotate(rootRot).add(pivot).add(rootPos)

        val bones = SkinRigBone.entries.associateWith { bone ->
            val rest = bone.rest(slim)
            val pose = frame.bones.getValue(bone)
            val rot = if (bone == SkinRigBone.HEAD) euler(headLook).mul(euler(pose.rotation)) else euler(pose.rotation)
            val scale = vec(pose.scale)
            val offset = vec(rest.center).sub(vec(rest.pivot)).mul(scale).rotate(rot)
            val center = vec(rest.pivot).add(vec(pose.position)).add(offset)
            val world = toWorld(center)
            RigPartTransform(
                translation = Vec3(world.x * PX * PLAYER_SCALE, world.y * PX * PLAYER_SCALE, world.z * PX * PLAYER_SCALE),
                rotation = quat(Quaterniond(rootRot).mul(rot)),
                scale = Vec3(
                    rest.size.x * scale.x / CUBE_PX * PLAYER_SCALE,
                    rest.size.y * scale.y / CUBE_PX * PLAYER_SCALE,
                    rest.size.z * scale.z / CUBE_PX * PLAYER_SCALE,
                ),
            )
        }

        val arm = SkinRigBone.RIGHT_ARM
        val armPose = frame.bones.getValue(arm)
        val armRot = euler(armPose.rotation)
        val joint = vec(arm.rest(slim).pivot).add(vec(armPose.position))
        val handRot = Quaterniond(rootRot).mul(armRot)
            .rotateX(Math.toRadians(HELD_TILT + HELD_ARM_PITCH))
        val handPos = toWorld(joint).add(Vector3d(HAND_OFFSET).rotate(handRot))
        val held = RigPartTransform(
            translation = Vec3(handPos.x * PX * PLAYER_SCALE, handPos.y * PX * PLAYER_SCALE, handPos.z * PX * PLAYER_SCALE),
            rotation = quat(handRot),
            scale = Vec3(PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE),
        )
        return RigTransforms(bones, held)
    }

    private fun euler(degrees: Vec3): Quaterniond =
        Quaterniond().rotateZ(Math.toRadians(degrees.z)).rotateY(Math.toRadians(degrees.y)).rotateX(Math.toRadians(degrees.x))

    private fun vec(v: Vec3): Vector3d = Vector3d(v.x, v.y, v.z)

    private fun quat(q: Quaterniond): RigQuat = RigQuat(q.x, q.y, q.z, q.w)
}

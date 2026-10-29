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
 *  - Hierarchy: [SkinRigBone.parent]. Head and arms are posed in the frame of `body`, which pivots at the waist;
 *    so `rig = root * body * arm` for an arm, `root * leg` for a leg (see [SkinRigBone]).
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
     * Pitch of the held display relative to the arm frame: the `rotX(90)` tilt plus vanilla's `poseRightArm` item lift
     * of 18 degrees (`xRot * 0.5 - PI/10`). It is part of the constant grip, not of the anatomical arm (ADR-0276).
     */
    public const val HELD_GRIP_PITCH: Double = HELD_TILT - 18.0

    /** Display origin of the held item in the right-arm frame (blocks, `PLAYER_SCALE` applied): the constant grip. */
    public val HELD_GRIP_OFFSET: Vec3 = run {
        val v = Vector3d(HAND_OFFSET).rotateX(Math.toRadians(HELD_GRIP_PITCH))
        Vec3(v.x * PX * PLAYER_SCALE, v.y * PX * PLAYER_SCALE, v.z * PX * PLAYER_SCALE)
    }

    /** Right-arm joint (blocks) and the arm's rotation in the rig frame (root x body x arm). */
    public data class ArmFrame(public val joint: Vec3, public val rotation: RigQuat)

    /**
     * A rigid transform `p -> rot * p + t` in rig pixels: one link of the bone chain. Composition `a * b` applies `b`
     * first (the child), then `a` (the parent).
     */
    private class Xf(val rot: Quaterniond, val t: Vector3d) {
        fun apply(p: Vector3d): Vector3d = Vector3d(p).rotate(rot).add(t)
        operator fun times(child: Xf): Xf = Xf(Quaterniond(rot).mul(child.rot), apply(child.t))
    }

    /** Rotation [rot] about [pivot], then translated by [offset]: `p -> pivot + offset + rot * (p - pivot)`. */
    private fun about(pivot: Vector3d, rot: Quaterniond, offset: Vector3d): Xf =
        Xf(rot, Vector3d(pivot).add(offset).sub(Vector3d(pivot).rotate(rot)))

    private fun rootXf(frame: RigFrame): Xf = about(vec(ROOT_PIVOT), euler(frame.root.rotation), vec(frame.root.position))

    /** Local link of [bone] (its own rotation / position around its joint, no scale) with an explicit [rot]. */
    private fun localXf(bone: SkinRigBone, frame: RigFrame, slim: Boolean, rot: Quaterniond): Xf =
        about(vec(bone.rest(slim).pivot), rot, vec(frame.bones.getValue(bone).position))

    /** Rig-frame transform of [bone]'s PARENT space: root, then the parent chain (the spine for head and arms). */
    private fun parentXf(bone: SkinRigBone, frame: RigFrame, slim: Boolean): Xf {
        val parent = bone.parent ?: return rootXf(frame)
        return parentXf(parent, frame, slim) * localXf(parent, frame, slim, euler(frame.bones.getValue(parent).rotation))
    }

    /** Right-hand grip (the held-item origin) in rig pixels, from the right arm's full chain. */
    private fun rightGripPx(frame: RigFrame, slim: Boolean): Vector3d {
        val arm = parentXf(SkinRigBone.RIGHT_ARM, frame, slim) *
            localXf(SkinRigBone.RIGHT_ARM, frame, slim, euler(frame.bones.getValue(SkinRigBone.RIGHT_ARM).rotation))
        // bone space: the joint at its rest pivot, the grip HAND_OFFSET away in the tilted held frame
        return arm.apply(Vector3d(HAND_OFFSET).rotateX(Math.toRadians(HELD_GRIP_PITCH)).add(vec(SkinRigBone.RIGHT_ARM.rest(slim).pivot)))
    }

    /**
     * Local rotation of the left arm for this frame: the authored one, turned by [RigFrame.leftHandGrip] toward an aim
     * of the arm's hanging axis (`-Y`) at the right-hand grip (look-at IK; the arm is one rigid bone, so the hand lands on
     * the line to the grip, at arm's length).
     */
    private fun leftArmRot(frame: RigFrame, slim: Boolean): Quaterniond {
        val authored = euler(frame.bones.getValue(SkinRigBone.LEFT_ARM).rotation)
        val w = frame.leftHandGrip.coerceIn(0.0, 1.0)
        if (w <= 0.0) return authored
        val parent = parentXf(SkinRigBone.LEFT_ARM, frame, slim)
        val joint = parent.apply(vec(SkinRigBone.LEFT_ARM.rest(slim).pivot).add(vec(frame.bones.getValue(SkinRigBone.LEFT_ARM).position)))
        val dirWorld = rightGripPx(frame, slim).sub(joint)
        if (dirWorld.lengthSquared() < 1e-9) return authored
        val dirLocal = dirWorld.normalize().rotate(Quaterniond(parent.rot).invert())
        val aim = Quaterniond().rotationTo(Vector3d(0.0, -1.0, 0.0), dirLocal)
        return if (w >= 1.0) aim else Quaterniond(authored).slerp(aim, w)
    }

    /** Local rotation of [bone] in this frame (the left arm may be aimed at the grip, see [leftArmRot]). */
    private fun boneRot(bone: SkinRigBone, frame: RigFrame, slim: Boolean): Quaterniond =
        if (bone == SkinRigBone.LEFT_ARM) leftArmRot(frame, slim) else euler(frame.bones.getValue(bone).rotation)

    /** The anatomical frame the held weapon hangs from (root x body x right arm); independent of the item pitch. */
    public fun rightArm(frame: RigFrame, slim: Boolean): ArmFrame = armFrame(SkinRigBone.RIGHT_ARM, frame, slim)

    /** Left-arm joint and rotation in the rig frame (after the two-hand grip aim, if any). */
    public fun leftArm(frame: RigFrame, slim: Boolean): ArmFrame = armFrame(SkinRigBone.LEFT_ARM, frame, slim)

    private fun armFrame(bone: SkinRigBone, frame: RigFrame, slim: Boolean): ArmFrame {
        val parent = parentXf(bone, frame, slim)
        val joint = parent.apply(vec(bone.rest(slim).pivot).add(vec(frame.bones.getValue(bone).position)))
        return ArmFrame(
            Vec3(joint.x * PX * PLAYER_SCALE, joint.y * PX * PLAYER_SCALE, joint.z * PX * PLAYER_SCALE),
            quat(Quaterniond(parent.rot).mul(boneRot(bone, frame, slim))),
        )
    }

    /**
     * Where the named [point] is in this frame, in the rig frame (blocks, `PLAYER_SCALE` applied; +X is the player's left, +Z front):
     * the point's offset from its bone's joint carried through the bone's full chain (root x spine x bone), the left-arm grip aim included.
     */
    public fun point(frame: RigFrame, slim: Boolean, point: RigPoint): Vec3 {
        val bone = point.bone
        val chain = parentXf(bone, frame, slim) * localXf(bone, frame, slim, boneRot(bone, frame, slim))
        val w = chain.apply(vec(bone.rest(slim).pivot).add(vec(point.offset)))
        return Vec3(w.x * PX * PLAYER_SCALE, w.y * PX * PLAYER_SCALE, w.z * PX * PLAYER_SCALE)
    }

    /** Hip pivot (blocks) and root rotation of the whole rig: the `hips` landmark. */
    public fun hips(frame: RigFrame): ArmFrame {
        val rootRot = euler(frame.root.rotation)
        val w = vec(ROOT_PIVOT).add(vec(frame.root.position))
        return ArmFrame(Vec3(w.x * PX * PLAYER_SCALE, w.y * PX * PLAYER_SCALE, w.z * PX * PLAYER_SCALE), quat(rootRot))
    }

    /** Waist pivot (blocks) and the spine's rig-frame rotation (root x body): the `spine` landmark. */
    public fun spine(frame: RigFrame, slim: Boolean): ArmFrame = armFrame(SkinRigBone.BODY, frame, slim)

    /**
     * @param headLook live head look as Euler degrees `(pitch about X, yaw about Y, 0)`, applied to the head bone
     * as an outer rotation on top of the animation (in the torso frame), so the head keeps following the camera.
     */
    public fun compute(frame: RigFrame, slim: Boolean, headLook: Vec3 = Vec3.ZERO): RigTransforms {
        val bones = SkinRigBone.entries.associateWith { bone ->
            val rest = bone.rest(slim)
            val pose = frame.bones.getValue(bone)
            val local = boneRot(bone, frame, slim)
            val rot = if (bone == SkinRigBone.HEAD) euler(headLook).mul(local) else local
            val chain = parentXf(bone, frame, slim) * localXf(bone, frame, slim, rot)
            val scale = vec(pose.scale)
            // box centre in the bone's own space: the joint plus the (scaled) rest offset; the chain moves it to the rig frame
            val world = chain.apply(vec(rest.center).sub(vec(rest.pivot)).mul(scale).add(vec(rest.pivot)))
            RigPartTransform(
                translation = Vec3(world.x * PX * PLAYER_SCALE, world.y * PX * PLAYER_SCALE, world.z * PX * PLAYER_SCALE),
                rotation = quat(chain.rot),
                scale = Vec3(
                    rest.size.x * scale.x / CUBE_PX * PLAYER_SCALE,
                    rest.size.y * scale.y / CUBE_PX * PLAYER_SCALE,
                    rest.size.z * scale.z / CUBE_PX * PLAYER_SCALE,
                ),
            )
        }

        val arm = rightArm(frame, slim)
        val armRot = Quaterniond(arm.rotation.x, arm.rotation.y, arm.rotation.z, arm.rotation.w)
        val handRot = Quaterniond(armRot).rotateX(Math.toRadians(HELD_GRIP_PITCH))
        val handPos = Vector3d(arm.joint.x, arm.joint.y, arm.joint.z)
            .add(Vector3d(HELD_GRIP_OFFSET.x, HELD_GRIP_OFFSET.y, HELD_GRIP_OFFSET.z).rotate(armRot))
        val held = RigPartTransform(
            translation = Vec3(handPos.x, handPos.y, handPos.z),
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

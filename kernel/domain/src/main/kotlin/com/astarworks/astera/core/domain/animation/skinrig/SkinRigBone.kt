package com.astarworks.astera.core.domain.animation.skinrig

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Rest geometry of one rig bone, in pixels (1/16 block) in the rig frame: origin on the floor
 * under the player, +Y up, the player's front toward +Z, the player's right hand toward -X
 * (the vanilla `PlayerModel` layout after the entity renderer's flip).
 *
 * @property center middle of the bone's box at rest.
 * @property size box extent (x, y, z) at rest.
 * @property pivot the vanilla joint the bone rotates around.
 */
public data class BoneRest(
    public val center: Vec3,
    public val size: Vec3,
    public val pivot: Vec3,
)

/**
 * The six bones of the PoC skin rig (ADR-0250). The boxes and limb joints are the vanilla 26.x
 * `PlayerModel` ones, so a rig at rest is indistinguishable from the real player.
 *
 * Hierarchy (two levels under the hip `root`):
 * ```
 * root (hips) -+- body (spine: pivot at the waist) -+- head
 *              |                                     +- right_arm (+ held item)
 *              |                                     +- left_arm
 *              +- right_leg
 *              +- left_leg
 * ```
 * [parent] says which bone a bone hangs from; a child's rotation / position are in its parent's frame, around its
 * own joint. Unlike vanilla, [BODY] pivots at the waist (12 px), so pitching / twisting the torso carries the head
 * and arms while the legs stay planted.
 *
 * [id] is what the server tells the client shader (see [SkinRigMarker]); the shader owns the
 * matching skin-UV table, so the two must stay in the same order.
 */
public enum class SkinRigBone(public val yamlName: String, public val id: Int) {
    HEAD("head", 0),
    BODY("body", 1),
    RIGHT_ARM("right_arm", 2),
    LEFT_ARM("left_arm", 3),
    RIGHT_LEG("right_leg", 4),
    LEFT_LEG("left_leg", 5),
    ;

    /** The bone this one hangs from (`null` = directly under `root`). */
    public val parent: SkinRigBone?
        get() = when (this) {
            HEAD, RIGHT_ARM, LEFT_ARM -> BODY
            BODY, RIGHT_LEG, LEFT_LEG -> null
        }

    /** True for the two bones whose box is 1 px narrower on a slim (Alex) skin. */
    public val isArm: Boolean get() = this == RIGHT_ARM || this == LEFT_ARM

    public fun rest(slim: Boolean): BoneRest = when (this) {
        HEAD -> BoneRest(Vec3(0.0, 28.0, 0.0), Vec3(8.0, 8.0, 8.0), Vec3(0.0, 24.0, 0.0))
        // The spine pivots at the waist (top of the legs), not at the vanilla neck joint: see the hierarchy above.
        BODY -> BoneRest(Vec3(0.0, 18.0, 0.0), Vec3(8.0, 12.0, 4.0), Vec3(0.0, 12.0, 0.0))
        // Classic arms hang from a joint 2 px below the shoulder line; slim arms sit 0.5 px lower (vanilla offset 2.5).
        RIGHT_ARM ->
            if (slim) BoneRest(Vec3(-5.5, 17.5, 0.0), Vec3(3.0, 12.0, 4.0), Vec3(-5.0, 21.5, 0.0))
            else BoneRest(Vec3(-6.0, 18.0, 0.0), Vec3(4.0, 12.0, 4.0), Vec3(-5.0, 22.0, 0.0))
        LEFT_ARM ->
            if (slim) BoneRest(Vec3(5.5, 17.5, 0.0), Vec3(3.0, 12.0, 4.0), Vec3(5.0, 21.5, 0.0))
            else BoneRest(Vec3(6.0, 18.0, 0.0), Vec3(4.0, 12.0, 4.0), Vec3(5.0, 22.0, 0.0))
        RIGHT_LEG -> BoneRest(Vec3(-1.9, 6.0, 0.0), Vec3(4.0, 12.0, 4.0), Vec3(-1.9, 12.0, 0.0))
        LEFT_LEG -> BoneRest(Vec3(1.9, 6.0, 0.0), Vec3(4.0, 12.0, 4.0), Vec3(1.9, 12.0, 0.0))
    }

    public companion object {
        public fun fromYamlName(name: String): SkinRigBone? = entries.firstOrNull { it.yamlName == name }

        public val yamlNames: List<String> = entries.map { it.yamlName }
    }
}

/**
 * How the server marks a rig display for the client shader without any custom packet: the
 * display's brightness override (`block`, `sky`) arrives in the vertex shader as the light UV.
 * Block light 15 is the marker (only an entity standing inside a light-15 source ever has it),
 * and the sky value carries `bone id + 8 when the arm is slim`.
 *
 * The shader patch (`content/shader-patches/core/entity.vsh.patch`) decodes exactly this;
 * change both together.
 */
public object SkinRigMarker {
    public const val BLOCK_LIGHT: Int = 15
    public const val SLIM_BIT: Int = 8

    /**
     * A ghost bone (ADR-0284) is the same bone drawn tinted and translucent by the shader. The sky nibble has 16 codes: 0..5 bones,
     * 10 / 11 slim arms, and the otherwise unused 6 / 7 (classic arms), 8 / 9 (head, body), 12 / 13 (legs), 14 / 15 (slim arms) carry
     * the ghost variants. The look is fixed in `entity.fsh.patch` ([GHOST_TINT_RGB], [GHOST_OPACITY]); change all three together.
     */
    public fun skyPayload(bone: SkinRigBone, slim: Boolean, ghost: Boolean = false): Int = when {
        !ghost -> bone.id + if (slim && bone.isArm) SLIM_BIT else 0
        bone == SkinRigBone.RIGHT_ARM -> if (slim) 14 else 6
        bone == SkinRigBone.LEFT_ARM -> if (slim) 15 else 7
        else -> bone.id + 8
    }

    /** The one ghost look the pack shader draws (`0xRRGGBB`). */
    public const val GHOST_TINT_RGB: Int = 0x8A5CFF

    /** The one ghost opacity the pack shader draws. */
    public const val GHOST_OPACITY: Double = 0.5
}

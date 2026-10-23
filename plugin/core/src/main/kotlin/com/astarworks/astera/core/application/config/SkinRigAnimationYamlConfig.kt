package com.astarworks.astera.core.application.config

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.skinrig.RigBoneTrack
import com.astarworks.astera.core.domain.animation.skinrig.RigChannel
import com.astarworks.astera.core.domain.animation.skinrig.RigKeyframe
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigAnimation
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone
import com.astarworks.astera.core.domain.animation.skinrig.SkinRigEase
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why an animation file could not become a [SkinRigAnimation]. */
public sealed class SkinRigConfigError {
    public abstract val message: String

    /** A field is out of range, malformed, or violates an animation invariant. */
    public data class Invalid(override val message: String) : SkinRigConfigError()
}

/**
 * YAML-shaped DTO for `content/animations/<id>.yaml` skin-rig animations (ADR-0250). Kept in
 * `application/config/` (kaml-annotated) so the domain [SkinRigAnimation] stays serialization-free.
 *
 * ```yaml
 * id: sword-swing
 * length_ticks: 12
 * loop: false
 * lock_movement: true
 * blend_in_ticks: 2
 * blend_out_ticks: 3
 * bones:
 *   right_arm:
 *     rotation:
 *       - { t: 0, v: [0, 0, 0] }
 *       - { t: 4, v: [-150, 0, 20], ease: ease_out }
 * ```
 */
@Serializable
public data class SkinRigAnimationYamlConfig(
    public val id: String,
    @SerialName("length_ticks") public val lengthTicks: Int,
    public val loop: Boolean = false,
    @SerialName("lock_movement") public val lockMovement: Boolean = false,
    @SerialName("blend_in_ticks") public val blendInTicks: Int = 0,
    @SerialName("blend_out_ticks") public val blendOutTicks: Int = 0,
    public val bones: Map<String, RigBoneTrackYaml> = emptyMap(),
    public val root: RigBoneTrackYaml? = null,
) {
    public fun toDefinition(): Result<SkinRigAnimation, SkinRigConfigError> = try {
        Result.success(
            SkinRigAnimation(
                id = CustomAnimationId(id),
                lengthTicks = lengthTicks,
                loop = loop,
                lockMovement = lockMovement,
                blendInTicks = blendInTicks,
                blendOutTicks = blendOutTicks,
                bones = bones.entries.associate { (name, track) ->
                    val bone = SkinRigBone.fromYamlName(name)
                        ?: throw IllegalArgumentException(
                            "unknown bone '$name' (the PoC rig has: ${SkinRigBone.yamlNames.joinToString(", ")})",
                        )
                    bone to track.toDomain("bones.$name")
                },
                root = root?.toDomain("root"),
            ),
        )
    } catch (e: IllegalArgumentException) {
        Result.failure(SkinRigConfigError.Invalid("animation '$id': ${e.message}"))
    }
}

/** The three optional channels of one bone (or `root`). */
@Serializable
public data class RigBoneTrackYaml(
    public val rotation: List<RigKeyframeYaml>? = null,
    public val position: List<RigKeyframeYaml>? = null,
    public val scale: List<RigKeyframeYaml>? = null,
) {
    public fun toDomain(where: String): RigBoneTrack = RigBoneTrack(
        rotation = rotation?.let { channel(it, "$where.rotation") },
        position = position?.let { channel(it, "$where.position") },
        scale = scale?.let { channel(it, "$where.scale") },
    )

    private fun channel(keys: List<RigKeyframeYaml>, where: String): RigChannel = try {
        RigChannel(keys.map { it.toDomain(where) })
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("$where: ${e.message}")
    }
}

/** `{ t: 4, v: [-150, 0, 20], ease: ease_out }` */
@Serializable
public data class RigKeyframeYaml(
    public val t: Int,
    public val v: List<Double>,
    public val ease: String? = null,
) {
    public fun toDomain(where: String): RigKeyframe {
        require(v.size == 3) { "$where t=$t: v needs 3 numbers, got ${v.size}" }
        return RigKeyframe(
            tick = t,
            value = Vec3(v[0], v[1], v[2]),
            ease = ease?.let {
                SkinRigEase.fromKey(it)
                    ?: throw IllegalArgumentException(
                        "$where t=$t: unknown ease '$it' (${SkinRigEase.entries.joinToString(" | ") { e -> e.key }})",
                    )
            } ?: SkinRigEase.LINEAR,
        )
    }
}

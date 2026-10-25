package com.astarworks.astera.core.domain.animation.skinrig

import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.geometry.Vec3

/** Curve INTO a keyframe from the previous one (ADR-0250). */
public enum class SkinRigEase(public val key: String) {
    LINEAR("linear"),
    STEP("step"),
    EASE_IN("ease_in"),
    EASE_OUT("ease_out"),
    EASE_IN_OUT("ease_in_out"),
    CATMULLROM("catmullrom"),
    ;

    public companion object {
        public fun fromKey(key: String): SkinRigEase? = entries.firstOrNull { it.key == key }
    }
}

/** One keyframe of a channel: value at [tick] and the curve that gets there. */
public data class RigKeyframe(
    public val tick: Int,
    public val value: Vec3,
    public val ease: SkinRigEase = SkinRigEase.LINEAR,
)

/** A sorted list of [RigKeyframe]s sampled at fractional ticks; holds the first / last value outside the range. */
public class RigChannel(public val keys: List<RigKeyframe>) {
    init {
        require(keys.isNotEmpty()) { "a channel needs at least one keyframe" }
        require(keys.first().tick >= 0) { "keyframe t must be >= 0 (got ${keys.first().tick})" }
        for (i in 1 until keys.size) {
            require(keys[i].tick > keys[i - 1].tick) {
                "keyframes must be strictly ascending by t (t=${keys[i].tick} follows t=${keys[i - 1].tick})"
            }
        }
    }

    public val lastTick: Int get() = keys.last().tick

    public fun sample(tick: Double): Vec3 {
        if (tick <= keys.first().tick) return keys.first().value
        if (tick >= keys.last().tick) return keys.last().value
        var i = 0
        while (keys[i + 1].tick <= tick) i++
        val a = keys[i]
        val b = keys[i + 1]
        val u = (tick - a.tick) / (b.tick - a.tick).toDouble()
        return when (b.ease) {
            SkinRigEase.STEP -> a.value
            SkinRigEase.LINEAR -> lerp(a.value, b.value, u)
            SkinRigEase.EASE_IN -> lerp(a.value, b.value, u * u)
            SkinRigEase.EASE_OUT -> lerp(a.value, b.value, 1.0 - (1.0 - u) * (1.0 - u))
            SkinRigEase.EASE_IN_OUT ->
                lerp(a.value, b.value, if (u < 0.5) 2.0 * u * u else 1.0 - 2.0 * (1.0 - u) * (1.0 - u))
            SkinRigEase.CATMULLROM -> {
                val p0 = if (i > 0) keys[i - 1].value else a.value
                val p3 = if (i + 2 < keys.size) keys[i + 2].value else b.value
                Vec3(
                    catmull(p0.x, a.value.x, b.value.x, p3.x, u),
                    catmull(p0.y, a.value.y, b.value.y, p3.y, u),
                    catmull(p0.z, a.value.z, b.value.z, p3.z, u),
                )
            }
        }
    }

    private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, u: Double): Double {
        val u2 = u * u
        val u3 = u2 * u
        return 0.5 * (
            2.0 * p1 + (-p0 + p2) * u + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * u2 +
                (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * u3
            )
    }
}

internal fun lerp(a: Vec3, b: Vec3, u: Double): Vec3 =
    Vec3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u)

/** The three optional channels of one bone (or of the whole rig). A missing channel stays at rest. */
public data class RigBoneTrack(
    public val rotation: RigChannel? = null,
    public val position: RigChannel? = null,
    public val scale: RigChannel? = null,
) {
    internal val lastTick: Int
        get() = maxOf(rotation?.lastTick ?: 0, position?.lastTick ?: 0, scale?.lastTick ?: 0)
}

/**
 * A keyframed pose animation for the skin rig (`content/animations/<id>.yaml`, ADR-0250).
 * Rotations are degrees relative to the rest pose around the bone's pivot, positions are pixels,
 * scales are multipliers; see [SkinRigTransforms] for the exact frame and rotation order.
 */
public class SkinRigAnimation(
    public val id: CustomAnimationId,
    public val lengthTicks: Int,
    public val loop: Boolean,
    public val lockMovement: Boolean,
    public val blendInTicks: Int,
    public val blendOutTicks: Int,
    public val bones: Map<SkinRigBone, RigBoneTrack>,
    public val root: RigBoneTrack?,
) {
    init {
        require(lengthTicks in 1..MAX_LENGTH_TICKS) { "length_ticks must be 1..$MAX_LENGTH_TICKS: $lengthTicks" }
        require(blendInTicks in 0..MAX_LENGTH_TICKS) { "blend_in_ticks must be 0..$MAX_LENGTH_TICKS: $blendInTicks" }
        require(blendOutTicks in 0..MAX_LENGTH_TICKS) { "blend_out_ticks must be 0..$MAX_LENGTH_TICKS: $blendOutTicks" }
        for ((bone, track) in bones) {
            require(track.lastTick <= lengthTicks) {
                "bone '${bone.yamlName}' has a keyframe past length_ticks=$lengthTicks (t=${track.lastTick})"
            }
        }
        root?.let {
            require(it.lastTick <= lengthTicks) { "root has a keyframe past length_ticks=$lengthTicks (t=${it.lastTick})" }
        }
    }

    public companion object {
        public const val MAX_LENGTH_TICKS: Int = 20 * 60
    }
}

/** Local pose of one bone relative to rest: Euler degrees, pixels, scale multiplier. */
public data class BonePose(
    public val rotation: Vec3 = Vec3.ZERO,
    public val position: Vec3 = Vec3.ZERO,
    public val scale: Vec3 = Vec3(1.0, 1.0, 1.0),
) {
    public companion object {
        public val REST: BonePose = BonePose()
    }
}

/** A sampled pose of every bone plus the whole-rig [root] transform; bones the animation does not touch are at rest. */
public data class RigFrame(
    public val root: BonePose,
    public val bones: Map<SkinRigBone, BonePose>,
) {
    public companion object {
        public val REST: RigFrame = RigFrame(BonePose.REST, SkinRigBone.entries.associateWith { BonePose.REST })
    }
}

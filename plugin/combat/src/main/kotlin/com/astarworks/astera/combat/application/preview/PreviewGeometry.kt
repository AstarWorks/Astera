package com.astarworks.astera.combat.application.preview

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/** Camera of a preview: [position] is the feet location (eye = +[PreviewGeometry.EYE_HEIGHT]), [lookAt] a world point. */
public data class ViewPoint(public val position: Vec3, public val lookAt: Vec3) {
    private val eye: Vec3 get() = Vec3(position.x, position.y + PreviewGeometry.EYE_HEIGHT, position.z)
    public val yaw: Float get() = PreviewGeometry.yawOf(lookAt - eye)
    public val pitch: Float get() = PreviewGeometry.pitchOf(lookAt - eye)
}

public enum class PreviewView(public val key: String) {
    SIDE("side"), OPPONENT("opponent"), BEHIND("behind"), STAY("stay");

    public companion object {
        public fun parse(raw: String): PreviewView? = entries.firstOrNull { it.key == raw.lowercase() }
    }
}

/** Where the dummies stand: [caster] and [target] feet positions, [axis] the horizontal unit vector caster -> target. */
public data class StageLayout(public val caster: Vec3, public val target: Vec3, public val axis: Vec3)

/** Pure view-point math for `/astera skill preview` (no Minecraft types). */
public object PreviewGeometry {
    public const val EYE_HEIGHT: Double = 1.62
    public const val BODY_CENTER: Double = 1.0
    public const val CASTER_AHEAD: Double = 3.0
    public const val TARGET_GAP: Double = 4.0
    public const val BEHIND_DISTANCE: Double = 4.0
    public const val SHOULDER_OFFSET: Double = 0.8
    public const val SHOULDER_RISE: Double = 0.6

    /** Caster [CASTER_AHEAD] blocks in front of [viewer] along [forward] (horizontal part), target [TARGET_GAP] beyond. */
    public fun layout(viewer: Vec3, forward: Vec3): StageLayout {
        val axis = horizontal(forward)
        val caster = viewer + axis * CASTER_AHEAD
        return StageLayout(caster, caster + axis * TARGET_GAP, axis)
    }

    /** Camera for [view] (`null` for [PreviewView.STAY]); [distance] only matters for [PreviewView.SIDE]. */
    public fun viewPoint(view: PreviewView, layout: StageLayout, distance: Double): ViewPoint? {
        val caster = layout.caster
        val target = layout.target
        val axis = layout.axis
        val perp = Vec3(-axis.z, 0.0, axis.x)
        val up = Vec3(0.0, BODY_CENTER, 0.0)
        return when (view) {
            PreviewView.STAY -> null
            PreviewView.SIDE -> {
                val mid = (caster + target) * 0.5
                ViewPoint(mid + perp * distance, mid + up)
            }
            PreviewView.OPPONENT -> ViewPoint(target, caster + up)
            PreviewView.BEHIND -> ViewPoint(
                caster - axis * BEHIND_DISTANCE + perp * -SHOULDER_OFFSET + Vec3(0.0, SHOULDER_RISE, 0.0),
                target + up,
            )
        }
    }

    /** Minecraft yaw (degrees, 0 = +Z, clockwise) of [direction]; 0 for a vertical / zero vector. */
    public fun yawOf(direction: Vec3): Float {
        if (direction.x == 0.0 && direction.z == 0.0) return 0f
        return Math.toDegrees(atan2(-direction.x, direction.z)).toFloat()
    }

    /** Minecraft pitch (degrees, positive = looking down) of [direction]. */
    public fun pitchOf(direction: Vec3): Float {
        val horizontal = sqrt(direction.x * direction.x + direction.z * direction.z)
        return Math.toDegrees(-atan2(direction.y, horizontal)).toFloat()
    }

    private fun horizontal(v: Vec3): Vec3 {
        val flat = Vec3(v.x, 0.0, v.z)
        return if (flat.lengthSquared() < 1e-9) Vec3(0.0, 0.0, 1.0) else flat.normalize()
    }
}

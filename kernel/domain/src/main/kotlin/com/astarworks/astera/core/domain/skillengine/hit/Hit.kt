package com.astarworks.astera.core.domain.skillengine.hit

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import java.util.UUID

/** Declarative hit volume, attached to frames by points (design 3.7). */
public sealed interface HitVolume {
    public data class Capsule(val from: PointRef, val to: PointRef, val radius: Value<Double>) : HitVolume
    public data class Sphere(val at: PointRef, val radius: Value<Double>) : HitVolume
    /**
     * A wedge in the plane spanned by [forward] and [left], apex at point [at] of [frame] (null = the frame origin),
     * opening [angleDeg], radius [range], and [height] = total thickness of the slab perpendicular to that plane,
     * centred on it. With `forward: caster.forward` (look incl. pitch) from `at: eye` the wedge follows the aim.
     */
    public data class Sector(
        val frame: String,
        val at: String?,
        val forward: AxisRef,
        val left: AxisRef,
        val angleDeg: Value<Double>,
        val range: Value<Double>,
        val height: Value<Double>,
    ) : HitVolume
}

/** How often one volume may hit the same victim. */
public sealed interface HitWindow {
    public data object Once : HitWindow
    public data class EveryTicks(val ticks: Int) : HitWindow
    public data object PerPart : HitWindow
}

/** A volume with its points and radii resolved to world coordinates for this tick. */
public sealed interface ResolvedVolume {
    /** Broad-phase sphere: all candidates within [broadRadius] of [broadCenter]. */
    public val broadCenter: Vec3
    public val broadRadius: Double

    public data class Capsule(val a: Vec3, val b: Vec3, val radius: Double) : ResolvedVolume {
        override val broadCenter: Vec3 get() = (a + b) * 0.5
        override val broadRadius: Double get() = a.distance(b) / 2.0 + radius
    }

    public data class Sphere(val center: Vec3, val radius: Double) : ResolvedVolume {
        override val broadCenter: Vec3 get() = center
        override val broadRadius: Double get() = radius
    }

    public data class Sector(
        val origin: Vec3,
        val forward: Vec3,
        val left: Vec3,
        val angleDeg: Double,
        val range: Double,
        val height: Double,
    ) : ResolvedVolume {
        override val broadCenter: Vec3 get() = origin
        override val broadRadius: Double get() = range + height
    }
}

/** Axis-aligned box in world space (victim hitbox). */
public data class HitBox(val min: Vec3, val max: Vec3) {
    public val center: Vec3 get() = (min + max) * 0.5
}

/** A candidate victim: id, feet position, body yaw, hitbox (from the `nearby` port). */
public data class HitCandidate(
    val entityId: UUID,
    val feet: Vec3,
    val yawDeg: Double,
    val box: HitBox,
    val velocity: Vec3 = Vec3(0.0, 0.0, 0.0),
)

/**
 * One contact (design 3.7 + F-4). point/normal are given in world space and in the victim frame
 * (entity root = feet + yaw), where they are invariant while the victim moves.
 */
public data class ContactEvent(
    val hitId: String,
    val tick: Int,
    val victim: UUID,
    val hitterFrame: String,
    val part: String?,
    val pointWorld: Vec3,
    val normalWorld: Vec3,
    val pointLocal: Vec3,
    val normalLocal: Vec3,
    val relVelocity: Vec3,
)

/**
 * Two-step resolution: broad (candidates already filtered by the caller via `IEntityQuery.nearby`
 * around [ResolvedVolume.broadCenter]/[ResolvedVolume.broadRadius]) and narrow (closest point on the
 * hitbox). Normal per F-4: normalize(point - closest point on the victim's vertical centre axis),
 * falling back to -(hitter velocity).
 */
public interface ContactResolver {
    public fun narrow(
        hitId: String,
        tick: Int,
        hitterFrame: String,
        volume: ResolvedVolume,
        hitterVelocity: Vec3,
        candidates: List<HitCandidate>,
    ): List<ContactEvent>
}

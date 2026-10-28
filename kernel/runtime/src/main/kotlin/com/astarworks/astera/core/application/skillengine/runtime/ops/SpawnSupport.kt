package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.OpContext
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Frame
import com.astarworks.astera.core.domain.skillengine.frame.FrameId
import com.astarworks.astera.core.domain.skillengine.hit.HitVolume
import com.astarworks.astera.core.domain.skillengine.hit.ResolvedVolume
import com.astarworks.astera.core.domain.skillengine.model.AxisRef
import com.astarworks.astera.core.domain.skillengine.model.LocationSpec
import com.astarworks.astera.core.domain.skillengine.model.PointRef
import com.astarworks.astera.core.domain.skillengine.model.TargetRef
import com.astarworks.astera.core.domain.skillengine.model.Value
import java.util.UUID

internal val WORLD: FrameId = FrameId("world")
internal val CASTER: FrameId = FrameId("caster")

/** True when the op's `when:` guard passes (absent guard passes). */
internal fun OpContext.passes(whenExpr: Value<Boolean>?): Boolean = whenExpr == null || values.bool(whenExpr)

internal fun OpContext.pointWorld(ref: PointRef, self: FrameId? = null): Vec3? {
    val frame = if (ref.frame == "self" && self != null) self else FrameId(ref.frame)
    return when (val r = tree.pointWorld(frame, ref.point)) {
        is com.astarworks.astera.core.domain.Result.Success -> r.value
        is com.astarworks.astera.core.domain.Result.Failure -> {
            host.failHere("point '$ref' does not resolve: ${r.error}")
            null
        }
    }
}

internal fun OpContext.locate(loc: LocationSpec): Vec3? = when (loc) {
    is LocationSpec.AtPoint -> pointWorld(loc.point)
    is LocationSpec.AtValue -> values.vec3(loc.value)
}

internal fun OpContext.feetOf(entity: UUID): Vec3? = ports.entities.rootOf(entity)?.feet

/** World position a target ref stands for (explosion centre, hit point fallback). */
internal fun OpContext.positionOf(scope: SkillCastScope, target: TargetRef): Vec3? = when (target) {
    TargetRef.Victim -> scope.contact?.victim?.let { feetOf(it) } ?: scope.contact?.pointWorld
    TargetRef.Caster -> feetOf(scope.caster)
    is TargetRef.Enemies -> pointWorld(target.around)
    is TargetRef.Allies -> pointWorld(target.around)
}

/** Entities a target ref stands for. Enemies exclude allies of the caster; allies are the caster's team mates, plus the caster only for `include_self`. */
internal fun OpContext.resolveTargets(scope: SkillCastScope, target: TargetRef): List<UUID> = when (target) {
    TargetRef.Victim -> listOfNotNull(scope.contact?.victim ?: host.currentVictim)
    TargetRef.Caster -> listOf(scope.caster)
    is TargetRef.Enemies -> pointWorld(target.around)?.let { at ->
        areaTargets(target, at, values.num(target.radius), scope.caster, ports.entities::nearby, scope::isAlly)
    } ?: emptyList()
    is TargetRef.Allies -> pointWorld(target.around)?.let { at ->
        areaTargets(target, at, values.num(target.radius), scope.caster, ports.entities::nearby, scope::isAlly)
    } ?: emptyList()
}

/**
 * THE area selector (damage, status, sound, control all reach it): entities within [radius] of the frame point [at],
 * allies split by team, `max_count` = the nearest N by feet distance from [at] (v1 TargeterResolver.kt:79-86 takes the first N of the query).
 */
internal fun areaTargets(
    target: TargetRef,
    at: Vec3,
    radius: Double,
    caster: UUID,
    nearby: (Vec3, Double, UUID?) -> List<com.astarworks.astera.core.domain.skillengine.hit.HitCandidate>,
    isAlly: (UUID) -> Boolean,
): List<UUID> = when (target) {
    is TargetRef.Enemies -> nearby(at, radius, caster).filter { !isAlly(it.entityId) }.nearestN(at, target.maxCount)
    is TargetRef.Allies -> {
        val mates = nearby(at, radius, caster).filter { it.entityId != caster && isAlly(it.entityId) }.nearestN(at, target.maxCount)
        if (target.includeSelf) listOf(caster) + mates else mates
    }
    else -> emptyList()
}

/** `max_count`: keep the [maxCount] candidates nearest to [at] (all of them when null). */
private fun List<com.astarworks.astera.core.domain.skillengine.hit.HitCandidate>.nearestN(at: Vec3, maxCount: Int?): List<UUID> =
    (if (maxCount == null) this else sortedBy { it.feet.distanceSquared(at) }.take(maxCount)).map { it.entityId }

/** Resolve a declarative volume to world coordinates; `self.*` points mean [self]. */
internal fun OpContext.resolveVolume(volume: HitVolume, self: FrameId): ResolvedVolume? = when (volume) {
    is HitVolume.Capsule -> {
        val a = pointWorld(volume.from, self)
        val b = pointWorld(volume.to, self)
        if (a == null || b == null) null else ResolvedVolume.Capsule(a, b, values.num(volume.radius))
    }
    is HitVolume.Sphere -> pointWorld(volume.at, self)?.let { ResolvedVolume.Sphere(it, values.num(volume.radius)) }
    is HitVolume.Sector -> {
        val frameId = if (volume.frame == "self") self else FrameId(volume.frame)
        val frame = tree.worldOf(frameId).getOrNull()
        if (frame == null) {
            null
        } else {
            ResolvedVolume.Sector(
                origin = volume.at?.let { tree.pointWorld(frameId, it).getOrNull() } ?: frame.origin,
                forward = axisDirection(frame, volume.forward),
                left = axisDirection(frame, volume.left),
                angleDeg = values.num(volume.angleDeg),
                range = values.num(volume.range),
                height = values.num(volume.height),
            )
        }
    }
}

private fun axisDirection(frame: Frame, axis: AxisRef): Vec3 {
    if (axis.name == "world.up") return Vec3(0.0, 1.0, 0.0)
    return when (axis.name.substringAfterLast('.')) {
        "left" -> frame.left()
        "right" -> frame.right()
        "up" -> frame.up()
        else -> frame.forward()
    }
}

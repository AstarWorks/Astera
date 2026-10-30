package com.astarworks.astera.core.application.skillengine.runtime.ops

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillEntityQuery
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.atan2

/**
 * The `blink` op (ADR-0303 3.1): where the caster lands and the teleport itself. The line from the feet to the requested point is cut to
 * `max` blocks, then stopped [BLOCK_MARGIN] short of the first block seen at foot or head height, then stopped at the front edge of the first
 * body ([DashClearance]), so the caster never ends inside a block or a hitbox. Stateless: one call per `blink` row.
 */
internal object BlinkResolver {
    private const val BLOCK_MARGIN: Double = 0.4
    private const val HEAD_INSET: Double = 0.1
    private const val BODY_REACH: Double = 3.0
    private const val EPS: Double = 1.0e-6

    /** Teleports the caster; answers a fault text, or null (also null for a blink to where the caster already stands). */
    fun blink(
        cast: CastRef,
        query: ISkillEntityQuery,
        movement: ISkillMovementGateway,
        dest: Vec3,
        max: Double,
        keepLook: Boolean,
        invulnerableTicks: Int,
    ): String? {
        val root = query.rootOf(cast, cast.caster) ?: return "blink: the caster has no pose"
        val from = root.feet
        val delta = dest - from
        val wanted = delta.length()
        if (wanted < EPS) return null
        val dir = delta * (1.0 / wanted)
        var travel = minOf(wanted, max)
        for (lift in doubleArrayOf(HEAD_INSET, root.height - HEAD_INSET)) {
            val up = Vec3(0.0, lift, 0.0)
            val hit = movement.probeBlock(cast, from + up, from + up + dir * travel) ?: continue
            travel = minOf(travel, ((hit.point - (from + up)).length() - BLOCK_MARGIN).coerceAtLeast(0.0))
        }
        var end = from + dir * travel
        val mid = (from + end) * 0.5
        val bodies = query.bodiesNear(cast, mid, travel / 2.0 + BODY_REACH, cast.caster).map { DashClearance.forbidden(it, root.height) }
        if (bodies.isNotEmpty()) DashClearance.frontEdge(bodies, from, end)?.let { end = it }
        val yaw = if (keepLook) null else Math.toDegrees(atan2(-dir.x, dir.z))
        movement.teleport(cast, cast.caster, end, yaw, null)
        if (invulnerableTicks > 0) movement.invulnerable(cast, cast.caster, invulnerableTicks)
        return null
    }
}

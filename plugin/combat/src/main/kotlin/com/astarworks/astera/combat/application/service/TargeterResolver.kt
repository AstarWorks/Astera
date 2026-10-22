package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.targeter.DirectionRef
import com.astarworks.astera.core.domain.skill.targeter.Targeter
import com.astarworks.astera.core.domain.skill.value.IntValue
import com.astarworks.astera.core.domain.skill.value.NumericValue
import com.astarworks.astera.core.domain.skill.value.VectorValue
import org.slf4j.Logger

/**
 * Resolves a [Targeter] to a concrete `List<PlayerId>` (for damage / status /
 * knockback) or a concrete `List<Vec3>` (for particle / sound spawn points).
 *
 * Basic targeters (Caster / Target / Origin / HitLocation / ProjectilePosition /
 * `*Offset`) resolve directly from [MechanicContext]. Area targeters go through
 * [INearbyTargetQuery]; the geometric ones (ForwardRay / Cone) additionally need
 * [poseLookup] for candidate positions and the caster's look direction
 * (ADR-0174) and return empty without it.
 *
 * Team filters ([Targeter.Enemies] / [Targeter.Allies]) use [teamLookup]; with
 * no team info Enemies = everyone nearby except the caster and Allies = the
 * caster only (when `includeSelf`).
 */
internal class TargeterResolver(
    private val nearbyQuery: INearbyTargetQuery,
    private val log: Logger,
    private val poseLookup: IPlayerPoseLookup? = null,
    private val teamLookup: IMatchTeamLookup? = null,
) {
    /** Evaluates a [Condition] against a context; wired by `MechanicDispatcher` (cycle-free). */
    var conditionGate: ((Condition, MechanicContext) -> Boolean)? = null

    fun resolvePlayers(targeter: Targeter, ctx: MechanicContext): List<PlayerId> = when (targeter) {
        Targeter.Caster -> listOf(ctx.actorId)
        Targeter.Target -> ctx.targetId?.let { listOf(it) } ?: emptyList()
        Targeter.Origin, Targeter.HitLocation, Targeter.ProjectilePosition ->
            emptyList() // Location-only targeters; no player resolution.
        is Targeter.CasterOffset, is Targeter.CasterForward, is Targeter.TargetOffset,
        is Targeter.ProjectileOffset, is Targeter.HitOffset ->
            emptyList()
        is Targeter.NearbyEntities -> {
            val center = resolveLocationOnly(targeter.center, ctx) ?: return emptyList()
            val radius = literal(targeter.radius)
            val found = nearbyQuery.findInRadius(center, radius, ctx.worldId)
            val limit = when (val count = targeter.maxCount) {
                is IntValue.Literal -> count.value.coerceAtLeast(0)
                is IntValue.Cel -> 0
            }
            found.asSequence()
                .filter { !targeter.excludeCaster || it != ctx.actorId }
                .distinct()
                .take(limit)
                .toList()
        }
        is Targeter.ForwardRay -> resolveForwardRay(targeter, ctx)
        is Targeter.Cone -> resolveCone(targeter, ctx)
        is Targeter.Enemies -> resolveEnemies(targeter, ctx)
        is Targeter.Allies -> resolveAllies(targeter, ctx)
        is Targeter.Filter -> resolveFilter(targeter, ctx)
        is Targeter.Random -> {
            val base = resolvePlayers(targeter.from, ctx)
            val n = when (val c = targeter.count) {
                is IntValue.Literal -> c.value
                is IntValue.Cel -> 0
            }
            base.shuffled().take(n)
        }
    }

    /**
     * Candidates of `base` for which the condition holds. The condition is evaluated once per
     * candidate with that candidate as the context `target` (so `target: { type: target }` inside
     * `has_status` means "this candidate").
     */
    private fun resolveFilter(t: Targeter.Filter, ctx: MechanicContext): List<PlayerId> {
        val base = resolvePlayers(t.base, ctx)
        val gate = conditionGate
        if (gate == null) {
            log.debug("Filter needs a condition gate (MechanicDispatcher); returning base unfiltered")
            return base
        }
        return base.filter { gate(t.condition, ctx.copy(targetId = it)) }
    }

    fun resolveLocations(targeter: Targeter, ctx: MechanicContext): List<Vec3> {
        return when (targeter) {
            Targeter.Caster, Targeter.Origin -> listOf(ctx.origin)
            Targeter.Target -> listOf(targetLocation(ctx))
            Targeter.HitLocation, Targeter.ProjectilePosition -> listOf(hitLocation(ctx))
            is Targeter.CasterOffset -> listOf(ctx.origin + literalVec(targeter.offset))
            is Targeter.CasterForward -> listOf(casterForward(targeter, ctx))
            is Targeter.TargetOffset -> listOf(targetLocation(ctx) + literalVec(targeter.offset))
            is Targeter.ProjectileOffset -> listOf(hitLocation(ctx) + literalVec(targeter.offset))
            is Targeter.HitOffset -> listOf(hitLocation(ctx) + literalVec(targeter.offset))
            is Targeter.NearbyEntities,
            is Targeter.ForwardRay,
            is Targeter.Cone,
            is Targeter.Enemies,
            is Targeter.Allies,
            is Targeter.Filter,
            is Targeter.Random -> {
                // One location per resolved player: live position when a pose
                // source is wired, else the cast origin.
                resolvePlayers(targeter, ctx).map { poseLookup?.poseOf(it)?.position ?: ctx.origin }
            }
        }
    }

    private fun casterForward(t: Targeter.CasterForward, ctx: MechanicContext): Vec3 {
        val pose = ctx.pose ?: return ctx.origin + Vec3(0.0, FALLBACK_EYE_HEIGHT + t.height, 0.0)
        val dir = pose.direction.normalize()
        return pose.eyePosition + dir * t.distance + Vec3(0.0, t.height, 0.0)
    }

    /** Location of `Targeter.Target`: live target position, else the impact point, else the origin. */
    fun targetLocation(ctx: MechanicContext): Vec3 =
        ctx.targetId?.let { poseLookup?.poseOf(it)?.position } ?: hitLocation(ctx)

    private fun hitLocation(ctx: MechanicContext): Vec3 = ctx.hitLocation ?: ctx.origin

    private fun resolveLocationOnly(targeter: Targeter, ctx: MechanicContext): Vec3? =
        resolveLocations(targeter, ctx).firstOrNull()

    // ---- geometric targeters ----------------------------------------------

    /** Ray / cone origin: the caster's eye when anchored on the caster, else the plain location. */
    private fun rayOrigin(from: Targeter, ctx: MechanicContext): Vec3? =
        if (from == Targeter.Caster) ctx.pose?.eyePosition ?: ctx.origin
        else resolveLocationOnly(from, ctx)

    /** Nearest player whose hitbox the ray enters within `distance` (first hit; non-piercing). */
    private fun resolveForwardRay(t: Targeter.ForwardRay, ctx: MechanicContext): List<PlayerId> {
        val dir = ctx.pose?.direction
        if (poseLookup == null || dir == null) {
            log.debug("ForwardRay needs IPlayerPoseLookup + caster pose; returning empty")
            return emptyList()
        }
        val origin = rayOrigin(t.from, ctx) ?: return emptyList()
        val distance = literal(t.distance)
        if (distance <= 0.0) return emptyList()
        val unit = dir.normalize()
        return candidates(origin, distance + BODY_HEIGHT, ctx)
            .mapNotNull { (id, feet) -> rayBoxEntry(origin, unit, feet)?.let { dist -> id to dist } }
            .filter { it.second <= distance }
            .minByOrNull { it.second }
            ?.let { listOf(it.first) }
            ?: emptyList()
    }

    /**
     * Slab test of the ray against the player's hitbox (0.6 x 1.8 x 0.6 at
     * [feet]). Returns the entry distance along the ray (0 when the origin is
     * already inside), or `null` on a miss.
     */
    private fun rayBoxEntry(origin: Vec3, dir: Vec3, feet: Vec3): Double? {
        val min = doubleArrayOf(feet.x - BODY_HALF_WIDTH, feet.y, feet.z - BODY_HALF_WIDTH)
        val max = doubleArrayOf(feet.x + BODY_HALF_WIDTH, feet.y + BODY_HEIGHT, feet.z + BODY_HALF_WIDTH)
        val o = doubleArrayOf(origin.x, origin.y, origin.z)
        val d = doubleArrayOf(dir.x, dir.y, dir.z)
        var tNear = 0.0
        var tFar = Double.POSITIVE_INFINITY
        for (i in 0..2) {
            if (kotlin.math.abs(d[i]) < 1e-12) {
                if (o[i] < min[i] || o[i] > max[i]) return null
            } else {
                val t1 = (min[i] - o[i]) / d[i]
                val t2 = (max[i] - o[i]) / d[i]
                tNear = maxOf(tNear, minOf(t1, t2))
                tFar = minOf(tFar, maxOf(t1, t2))
                if (tNear > tFar) return null
            }
        }
        return tNear
    }

    /**
     * Players inside a cone. [Targeter.Cone.angleDegrees] is the full apex
     * angle (half-angle = angle / 2). Ordered nearest first.
     */
    private fun resolveCone(t: Targeter.Cone, ctx: MechanicContext): List<PlayerId> {
        if (poseLookup == null) {
            log.debug("Cone needs IPlayerPoseLookup; returning empty")
            return emptyList()
        }
        val dir = when (val d = t.direction) {
            DirectionRef.FromCaster, DirectionRef.FromHitNormal -> ctx.pose?.direction
            DirectionRef.Up -> Vec3(0.0, 1.0, 0.0)
            is DirectionRef.Custom -> literalVec(d.vector)
            // Radial refs have no single cone axis; the caster's look is the sensible axis.
            is DirectionRef.AwayFromCaster, is DirectionRef.TowardCaster -> ctx.pose?.direction
        }?.normalize()?.takeIf { it.lengthSquared() > 0.0 }
        if (dir == null) {
            log.debug("Cone has no resolvable direction; returning empty")
            return emptyList()
        }
        val origin = rayOrigin(t.from, ctx) ?: return emptyList()
        val distance = literal(t.distance)
        return ConeSelector.select(
            origin = origin,
            direction = dir,
            range = distance,
            angleDegrees = literal(t.angleDegrees),
            candidates = nearbyTargets(origin, distance, ctx),
        ).map { it.id }.let { ids -> t.maxCount?.let { ids.take(it.coerceAtLeast(0)) } ?: ids }
    }

    private fun resolveEnemies(t: Targeter.Enemies, ctx: MechanicContext): List<PlayerId> {
        val center = resolveLocationOnly(t.center, ctx) ?: return emptyList()
        val found = nearbyQuery.findInRadius(center, t.radius?.let(::literal) ?: TEAM_TARGETER_RADIUS, ctx.worldId)
        val myTeam = teamLookup?.teamOf(ctx.actorId)
        return found.filter { it != ctx.actorId && (myTeam == null || teamLookup?.teamOf(it) != myTeam) }
    }

    private fun resolveAllies(t: Targeter.Allies, ctx: MechanicContext): List<PlayerId> {
        val center = resolveLocationOnly(t.center, ctx) ?: return emptyList()
        val found = nearbyQuery.findInRadius(center, t.radius?.let(::literal) ?: TEAM_TARGETER_RADIUS, ctx.worldId)
        val myTeam = teamLookup?.teamOf(ctx.actorId)
        val mates = if (myTeam == null) {
            emptyList()
        } else {
            found.filter { it != ctx.actorId && teamLookup?.teamOf(it) == myTeam }
        }
        return if (t.includeSelf) listOf(ctx.actorId) + mates else mates
    }

    /** Nearby targets (players and mobs, minus the caster) with a known feet position. */
    private fun nearbyTargets(origin: Vec3, radius: Double, ctx: MechanicContext): List<NearbyTarget> =
        nearbyQuery.findNearby(origin, radius, ctx.worldId)
            .asSequence()
            .filter { it.id != ctx.actorId }
            .distinctBy { it.id }
            .mapNotNull { t ->
                val feet = t.position ?: poseLookup?.poseOf(t.id)?.position ?: return@mapNotNull null
                t.copy(position = feet)
            }
            .toList()

    /** Nearby players/mobs (minus the caster) with their feet position. */
    private fun candidates(origin: Vec3, radius: Double, ctx: MechanicContext): List<Pair<PlayerId, Vec3>> =
        nearbyTargets(origin, radius, ctx).map { it.id to it.position!! }

    private fun literal(v: NumericValue): Double = when (v) {
        is NumericValue.Literal -> v.value
        is NumericValue.Cel -> 0.0
    }

    private fun literalVec(v: VectorValue): Vec3 = when (v) {
        is VectorValue.Literal -> v.value
        is VectorValue.Cel -> Vec3.ZERO
    }

    companion object {
        /** Player hitbox (standing): 0.6 wide, 1.8 tall. */
        const val BODY_HALF_WIDTH: Double = 0.3
        const val BODY_HEIGHT: Double = 1.8

        /** Eye height used when a cast carries no pose. */
        const val FALLBACK_EYE_HEIGHT: Double = 1.5

        /**
         * `Targeter.Enemies` / `Allies` scan this many blocks around `center`
         * unless they carry an explicit `radius`.
         */
        const val TEAM_TARGETER_RADIUS: Double = 10.0
    }
}

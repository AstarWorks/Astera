package com.astarworks.astera.match.application.gamemode.annihilation

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.nexus.IgnoreReason
import com.astarworks.astera.core.domain.nexus.NexusDamageOutcome
import com.astarworks.astera.core.domain.nexus.NexusState
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Annihilation pillars as combat / world targets (WP10): the [IStructureTargets]
 * implementation Combat consumes, so skill hits (projectile, AoE, `damage`
 * mechanics, melee against the pillar target) and enemy block breaks all end in
 * [AnnihilationMatchTracker.recordDamage].
 *
 * Rules enforced here (not in the tracker, which knows no players):
 *  - the attacker must be in the same match as the pillar;
 *  - a team cannot damage its own pillars ([IStructureTargets.Rejection.FRIENDLY]);
 *  - phase gating (Build immunity, tier opening) stays with the tracker's pillar
 *    state: damage to a pillar that is not [NexusState.VULNERABLE] is rejected as
 *    [IStructureTargets.Rejection.NOT_VULNERABLE] and changes nothing.
 *
 * Pillars without a stage site (no `pillars:` in the stage) are invisible here.
 * Main thread only (same contract as the tracker).
 */
public class AnnihilationPillarTargets(
    private val tracker: AnnihilationMatchTracker,
    private val matches: IMatchRepository,
    private val scheduler: IScheduler,
) : IStructureTargets {

    private data class Hit(
        val matchId: MatchId,
        val view: AnnihilationMatchTracker.PillarView,
        val targetId: PlayerId,
    )

    /** A pillar block hit by [attacker] breaking it. */
    public sealed interface BlockBreakVerdict {
        /** Not a pillar block: the break is none of our business. */
        public data object NotAPillar : BlockBreakVerdict

        /** A pillar block: the break must be cancelled; [damaged] says whether HP was taken. */
        public data class Pillar(val damaged: Boolean) : BlockBreakVerdict
    }

    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<IStructureTargets.StructureTarget> {
        val r2 = radius * radius
        // Only pillars that can take damage are aimed at (a Build-phase pillar is immune).
        return standing()
            .filter { it.view.nexus.state == NexusState.VULNERABLE }
            .filter { world == null || it.view.world == world }
            .filter { it.view.site!!.centre.distanceSquared(center) <= r2 }
            .map { IStructureTargets.StructureTarget(it.targetId, it.view.site!!.centre, it.view.world!!) }
            .toList()
    }

    override fun isStructure(id: PlayerId): Boolean = all().any { it.targetId == id }

    override fun targetAtBlock(world: WorldId?, blockPosition: Vec3): IStructureTargets.StructureTarget? =
        standing()
            .firstOrNull { (world == null || it.view.world == world) && sameBlock(it.view.site!!.position, blockPosition) }
            ?.let { IStructureTargets.StructureTarget(it.targetId, it.view.site!!.centre, it.view.world!!) }

    override fun damage(target: PlayerId, attacker: PlayerId?, amount: Double): Result<Double, IStructureTargets.Rejection> {
        val hit = all().firstOrNull { it.targetId == target }
            ?: return Result.failure(IStructureTargets.Rejection.UNKNOWN_TARGET)
        if (attacker == null) return Result.failure(IStructureTargets.Rejection.NO_ATTACKER)
        val attackerMatch = matches.findMatchOf(attacker)
        if (attackerMatch?.id != hit.matchId) return Result.failure(IStructureTargets.Rejection.NO_ATTACKER)
        val attackerTeam = attackerMatch.teams.firstOrNull { attacker in it.members }?.id
            ?: return Result.failure(IStructureTargets.Rejection.NO_ATTACKER)
        if (attackerTeam == hit.view.nexus.owningTeam) return Result.failure(IStructureTargets.Rejection.FRIENDLY)

        val points = ceil(amount).toInt().coerceAtLeast(1)
        return when (val outcome = tracker.recordDamage(hit.matchId, hit.view.nexus.id, points, attacker, scheduler.currentTick())) {
            is NexusDamageOutcome.Damaged -> Result.success(outcome.currentHp.toDouble())
            is NexusDamageOutcome.Destroyed -> Result.success(0.0)
            is NexusDamageOutcome.Ignored -> Result.failure(
                when (outcome.reason) {
                    IgnoreReason.BUILDING -> IStructureTargets.Rejection.NOT_VULNERABLE
                    IgnoreReason.ZERO_AMOUNT -> IStructureTargets.Rejection.NOT_VULNERABLE
                    IgnoreReason.ALREADY_DESTROYED -> IStructureTargets.Rejection.UNKNOWN_TARGET
                },
            )
        }
    }

    /**
     * A block break at [blockPosition] in [world] by [player]. If it is a standing
     * pillar block it deals the match's `pillarBreakDamage` to it (subject to the
     * same rules as any other hit) and the caller must cancel the vanilla break.
     * A destroyed pillar's block is already gone, so it is [BlockBreakVerdict.NotAPillar].
     */
    public fun onBlockBreak(player: PlayerId, world: WorldId?, blockPosition: Vec3): BlockBreakVerdict {
        val hit = standing().firstOrNull {
            (world == null || it.view.world == world) && sameBlock(it.view.site!!.position, blockPosition)
        } ?: return BlockBreakVerdict.NotAPillar
        val perBreak = tracker.state(hit.matchId)?.config?.pillarBreakDamage ?: return BlockBreakVerdict.NotAPillar
        return BlockBreakVerdict.Pillar(damaged = damage(hit.targetId, player, perBreak.toDouble()) is Result.Success)
    }

    override fun hitPoints(id: PlayerId): Double? =
        all().firstOrNull { it.targetId == id }?.view?.nexus?.hp?.toDouble()

    override fun maxHitPoints(id: PlayerId): Double? =
        all().firstOrNull { it.targetId == id }?.view?.nexus?.maxHp?.toDouble()

    private fun all(): Sequence<Hit> = tracker.allActive().asSequence().flatMap { matchId ->
        tracker.pillarViews(matchId).asSequence()
            .filter { it.site != null && it.world != null }
            .map { Hit(matchId, it, idOf(it.nexus.id.value)) }
    }

    private fun standing(): Sequence<Hit> = all().filter { it.view.nexus.state != NexusState.DESTROYED }

    private fun sameBlock(a: Vec3, b: Vec3): Boolean =
        floor(a.x) == floor(b.x) && floor(a.y) == floor(b.y) && floor(a.z) == floor(b.z)

    public companion object {
        /** Stable synthetic combat-target id of a pillar. */
        public fun idOf(nexusId: String): PlayerId =
            PlayerId(UUID.nameUUIDFromBytes("astera-pillar:$nexusId".toByteArray()))
    }
}

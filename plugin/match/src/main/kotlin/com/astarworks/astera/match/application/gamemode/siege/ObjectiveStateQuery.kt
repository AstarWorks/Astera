package com.astarworks.astera.match.application.gamemode.siege

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId

/** Read-only window into a running objective mode (bots, tooling). Never mutates the match. */
public interface IObjectiveStateQuery {
    /** The live state of the objective of [matchId], or `null` when the match does not run this mode. */
    public fun objectiveSnapshot(matchId: MatchId): ObjectiveSnapshot?
}

/** Live objective state of one match, one variant per mode. */
public sealed interface ObjectiveSnapshot {

    /** Splat Zones: who owns each `wool-*` zone, who is filling it, and the pending penalties. */
    public data class Zones(
        val zones: Map<String, Zone>,
        /** Seconds of penalty a team still has to burn before its count moves again. */
        val penaltySeconds: Map<TeamId, Int>,
    ) : ObjectiveSnapshot {
        public data class Zone(val centre: Vec3, val owner: TeamId?, val challenger: TeamId?, val progress: Double)
    }

    /** Rainmaker: where the TNT lies, who carries it, the shield, and each team's furthest route progress (0..1). */
    public data class Rainmaker(
        /** Position of the loose TNT, or of the carrier while carried; `null` while it is respawning. */
        val position: Vec3?,
        val carrier: PlayerId?,
        val shieldHp: Double,
        val shieldMax: Double,
        val progress: Map<TeamId, Double>,
        /** The next checkpoint centre the carrier of [TeamId] has to hold, or the goal when none is left. */
        val nextWaypoint: Map<TeamId, Vec3>,
        val goal: Map<TeamId, Vec3>,
    ) : ObjectiveSnapshot

    /** Clam Blitz: free clams, per-basket barrier state, who carries how many, and who holds a power TNT. */
    public data class Clams(
        val freeClams: Map<String, Vec3>,
        val piles: List<Pair<Vec3, Int>>,
        val baskets: Map<TeamId, Basket>,
        val carried: Map<PlayerId, Int>,
        val powerHolders: Set<PlayerId>,
    ) : ObjectiveSnapshot {
        public data class Basket(val centre: Vec3, val open: Boolean, val secondsLeft: Int)
    }

    /** Steal Jewelry: jewels left on each pedestal and carried by whom. */
    public data class Jewels(
        val onPedestal: Map<TeamId, Int>,
        val pedestals: Map<TeamId, Vec3>,
        val carried: Map<PlayerId, Int>,
    ) : ObjectiveSnapshot
}

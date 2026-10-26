package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (ADR-0108, Wave 5annihilation): a team's Tier 3 (Nexus
 * proper) pillar fell. The team's remaining players are out — Hypixel-style
 * "your Nexus has fallen!" — and the match's surviving-team count drops.
 *
 * # When fired
 *
 * `AnnihilationMatchTracker`, immediately after publishing the
 * [NexusDestroyed] event for the Tier 3 pillar. Always fires exactly once
 * per team per match (a team can only be eliminated once; the tracker
 * tracks "already eliminated" to make the listener idempotent).
 *
 * # Subscribers
 *
 * - **AnnihilationGameMode**: triggers the win-condition re-check. If
 *   `surviving teams <= 1` → publish `MatchEnded` (with the surviving team
 *   as winner, or null on simultaneous elimination → draw).
 * - HUD: "team X eliminated" broadcast banner.
 * - Audit log: `annihilation.team.eliminated`.
 *
 * # [survivingPlayers]
 *
 * Snapshot of the *eliminated team's* roster — useful for the announcement
 * ("Players A, B, C eliminated") and for downstream stats. The actual
 * spectator-mode flip happens in Wave 6's Paper adapter (this event is the
 * trigger).
 */
public data class TeamEliminated(
    public val matchId: MatchId,
    public val teamId: TeamId,
    public val survivingPlayers: Set<PlayerId>,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))
}

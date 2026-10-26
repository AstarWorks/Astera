package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.game.InstanceId

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (ADR-0108, Wave 5annihilation): the lethal hit that
 * dropped a Nexus pillar's HP to zero.
 *
 * Always fires in addition to (and *after*) the final [NexusDamaged]
 * carrying the same hit — no, actually: per the wave's domain
 * `Nexus.applyDamage`, a lethal hit returns `NexusDamageOutcome.Destroyed`
 * directly and the tracker emits *only* [NexusDestroyed] for that hit (no
 * trailing [NexusDamaged]). This matches Hypixel-style "Nexus destroyed!"
 * broadcasts being a discrete event, not a damage-stream tail.
 *
 * # Subscribers
 *
 * - **AnnihilationMatchTracker** itself — applies the per-tier debuff:
 *    - Tier 1 lost → owning team's respawn delay + N seconds.
 *    - Tier 2 lost → owning team's resource regen × debuff multiplier.
 *    - Tier 3 lost → fires [TeamEliminated], cascading to win-condition
 *      check.
 * - HUD: red flash + "team X lost Nexus tier Y" toast.
 * - Audit log: `annihilation.nexus.destroyed`.
 *
 * # [attackerId] nullability
 *
 * Null when destroyed by environment / DoT (Phase 4 sudden-death tick,
 * deliberate cleanup by admin command). Subscribers that credit kills
 * (Wave 5combat-depth focus-fire) skip null-attacker events.
 */
public data class NexusDestroyed(
    public val matchId: MatchId,
    public val nexusId: NexusId,
    public val owningTeam: TeamId,
    public val tier: NexusPillarTier,
    public val attackerId: PlayerId?,
    public val previousHp: Int,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Instance(InstanceId(matchId.value))

    init {
        require(previousHp > 0) {
            "NexusDestroyed.previousHp must be > 0 (lethal hit); got=$previousHp"
        }
    }
}

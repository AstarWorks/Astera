package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.nexus.NexusId
import com.astarworks.astera.core.domain.nexus.NexusPillarTier
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Cross-plugin event (ADR-0108, Wave 5annihilation): a [NexusId] took damage
 * but did not get destroyed by this hit.
 *
 * # When fired
 *
 * `AnnihilationMatchTracker.recordDamage` after applying a non-lethal
 * `Nexus.applyDamage` call. The event fires once per hit; multi-hit combos
 * publish multiple events.
 *
 * # Subscribers
 *
 * - HUD: update the team's "Nexus HP bar" overlay in the lobby scoreboard.
 * - Audit log: `annihilation.nexus.damaged` row (Wave 5e audit).
 * - Wave 5combat-depth focus-fire scoring: attacker damage credit.
 *
 * # Why a separate event from [NexusDestroyed]?
 *
 * Two different downstream actions:
 *
 *  - Damage-only consumers (HUD, focus-fire) want every hit.
 *  - Destruction-only consumers (team-elimination listener, debuff
 *    applicator) want only the final hit.
 *
 * Splitting the events lets each consumer subscribe to exactly the right
 * lifecycle without filtering.
 *
 * # [attackerId] nullability
 *
 * Null when damage was dealt by environment / DoT (Phase 4 sudden-death
 * tick, lava, etc.). Subscribers that need an attacker (focus-fire credit)
 * skip null-attacker events.
 */
public data class NexusDamaged(
    public val matchId: MatchId,
    public val nexusId: NexusId,
    public val owningTeam: TeamId,
    public val tier: NexusPillarTier,
    public val attackerId: PlayerId?,
    public val damageAmount: Int,
    public val previousHp: Int,
    public val currentHp: Int,
    public val atTick: Long,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    init {
        require(damageAmount > 0) {
            "NexusDamaged.damageAmount must be > 0 (got=$damageAmount, id=${nexusId.value})"
        }
        require(previousHp > currentHp) {
            "NexusDamaged: previousHp ($previousHp) must be > currentHp ($currentHp)"
        }
        require(currentHp > 0) {
            "NexusDamaged.currentHp must be > 0 (use NexusDestroyed for lethal hits); got=$currentHp"
        }
    }
}

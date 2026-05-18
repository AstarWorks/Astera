package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.core.domain.match.TeamId

/**
 * Policy: should a damage attempt between two players be allowed?
 *
 * Wave 5a introduces two trivial policies; richer policies (warmup grace,
 * mode-specific overrides) can be layered later.
 *
 * When team data is unavailable (`null`), policies err on the side of *allow*
 * — a missing team means single-player smoke tests or a player not yet in a
 * match, and we'd rather risk an over-allowed hit than silently drop damage.
 */
public sealed class FriendlyFirePolicy {

    public abstract fun shouldBlock(attackerTeam: TeamId?, defenderTeam: TeamId?): Boolean

    /** Always allow — no friendly-fire restriction. */
    public data object Allow : FriendlyFirePolicy() {
        override fun shouldBlock(attackerTeam: TeamId?, defenderTeam: TeamId?): Boolean = false
    }

    /**
     * Block damage when attacker and defender are on the same team. Self-damage
     * (attacker == defender, signalled by equal TeamIds) is also blocked — use
     * [Allow] for game modes that permit AoE self-damage.
     */
    public data object BlockSameTeam : FriendlyFirePolicy() {
        override fun shouldBlock(attackerTeam: TeamId?, defenderTeam: TeamId?): Boolean {
            if (attackerTeam == null || defenderTeam == null) return false
            return attackerTeam == defenderTeam
        }
    }
}

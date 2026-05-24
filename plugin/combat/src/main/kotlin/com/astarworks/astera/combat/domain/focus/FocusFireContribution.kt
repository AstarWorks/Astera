package com.astarworks.astera.combat.domain.focus

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * One attacker's running contribution to an ongoing focus-fire window
 * (Wave 5combat-depth, ADR-0105).
 *
 * Tracked inside [FocusFireState] so the tracker can decide which
 * attackers count toward the threshold (a player whose last hit is
 * older than the window is no longer contributing).
 *
 * @property attacker Player who landed the hits.
 * @property totalDamage Sum of damage dealt within the active window.
 * @property hitCount Number of hits within the active window. Useful
 *   for "balanced focus" audits (one player landing 100 hits and two
 *   landing zero is degenerate).
 * @property lastHitAtTick Server tick of this attacker's most-recent
 *   contribution.
 */
public data class FocusFireContribution(
    public val attacker: PlayerId,
    public val totalDamage: Double,
    public val hitCount: Int,
    public val lastHitAtTick: Long,
) {
    init {
        require(totalDamage.isFinite() && totalDamage >= 0.0) {
            "FocusFireContribution.totalDamage must be finite and >= 0: $totalDamage"
        }
        require(hitCount > 0) {
            "FocusFireContribution.hitCount must be > 0: $hitCount"
        }
        require(lastHitAtTick >= 0L) {
            "FocusFireContribution.lastHitAtTick must be >= 0: $lastHitAtTick"
        }
    }
}

package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `IFocusFireTracker` the tick a focus-fire window crosses
 * its activation threshold (Wave 5combat-depth, ADR-0105).
 *
 * Subscribers:
 *  - HUD: paint the focus-fire icon over the victim's nameplate.
 *  - Advancements: count "team focus-fire 10 times" criteria.
 *  - Battle pass: emit XP for "focus-fire participant".
 *
 * Fires exactly once per window. If the window deactivates (attackers
 * drop below threshold) and re-activates later, that's a fresh event.
 *
 * @property victim Player taking the focused damage.
 * @property attackers Unordered list of players currently contributing.
 * @property totalDamage Sum of damage from every contributor within
 *   the active window.
 * @property atTick Server tick at activation.
 */
public data class FocusFireActivated(
    public val victim: PlayerId,
    public val attackers: List<PlayerId>,
    public val totalDamage: Double,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(victim.uuid)

    init {
        require(attackers.size >= 2) {
            "FocusFireActivated.attackers must have >= 2 entries: $attackers"
        }
        require(attackers.size == attackers.toSet().size) {
            "FocusFireActivated.attackers must be distinct: $attackers"
        }
        require(victim !in attackers) {
            "FocusFireActivated.attackers must not contain the victim: $victim"
        }
        require(totalDamage.isFinite() && totalDamage >= 0.0) {
            "FocusFireActivated.totalDamage must be finite and >= 0: $totalDamage"
        }
        require(atTick >= 0L) {
            "FocusFireActivated.atTick must be >= 0: $atTick"
        }
    }
}

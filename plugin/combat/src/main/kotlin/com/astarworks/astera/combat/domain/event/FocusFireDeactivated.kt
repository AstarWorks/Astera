package com.astarworks.astera.combat.domain.event

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Published by `IFocusFireTracker` when an active focus-fire window
 * collapses — either because contributors dropped below the threshold
 * or because the window aged out (Wave 5combat-depth, ADR-0105).
 *
 * Pair event to [FocusFireActivated]. Subscribers can rely on every
 * `Activated` being followed by exactly one `Deactivated` for the same
 * victim (unless the server shuts down mid-window).
 *
 * @property victim Player whose focused damage window expired.
 * @property durationTicks How long the window stayed active.
 * @property atTick Server tick at deactivation.
 */
public data class FocusFireDeactivated(
    public val victim: PlayerId,
    public val durationTicks: Int,
    public val atTick: Long,
    override val version: Int = 1,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent {
    init {
        require(durationTicks >= 0) {
            "FocusFireDeactivated.durationTicks must be >= 0: $durationTicks"
        }
        require(atTick >= 0L) {
            "FocusFireDeactivated.atTick must be >= 0: $atTick"
        }
    }
}

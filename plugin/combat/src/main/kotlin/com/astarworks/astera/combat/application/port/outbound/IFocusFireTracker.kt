package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.focus.FocusFireConfig
import com.astarworks.astera.combat.domain.focus.FocusFireState
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: team-coordination focus-fire detector (Wave 5combat-depth,
 * ADR-0105).
 *
 * Listens to damage events and detects when at least
 * [FocusFireConfig.minAttackers] distinct players have damaged the same
 * victim within [FocusFireConfig.windowTicks]. On activation publishes
 * `FocusFireActivated`; on collapse publishes `FocusFireDeactivated`.
 *
 * # Why a port, not a domain object
 *
 * The detection logic itself is pure (and lives in `FocusFireState`),
 * but the *integration* — listening to damage, pruning stale windows
 * on a scheduler, calling `IDomainEventBus.publish` — is application-
 * layer concern. The port keeps that orchestration testable.
 *
 * # Why session-scoped (no persistence)
 *
 * Same rationale as `IComboTracker`: focus-fire windows are tactical,
 * not progression. They reset on reboot. Per ADR-0105.
 *
 * # Friendly-fire interaction
 *
 * The tracker leaves friendly-fire filtering to the caller. If the
 * caller passes a team-mate hit (e.g., when friendly-fire is enabled
 * for a custom mode), it counts. Wave 5q-fix2 friendly-fire policy
 * sits *above* this port and prevents the hit from reaching the
 * tracker in normal team modes.
 */
public interface IFocusFireTracker {

    /** Replace the tuning config (called once at boot + on reload). */
    public fun configure(config: FocusFireConfig)

    /** Current config. Defaults to [FocusFireConfig.DEFAULT] until [configure]. */
    public fun config(): FocusFireConfig

    /**
     * Record a damage hit from [attacker] on [victim] at [atTick]. The
     * tracker:
     *  1. Prunes stale contributions outside the active window.
     *  2. Appends / increments the [attacker]'s contribution.
     *  3. Re-evaluates activation; publishes events on edge transitions.
     */
    public fun recordHit(
        attacker: PlayerId,
        victim: PlayerId,
        damageDealt: Double,
        atTick: Long,
    )

    /**
     * Snapshot of the focus-fire state on [victim], or null if none
     * (no recent hits, or pruned to empty). The tracker may prune
     * in-line using [nowTick].
     */
    public fun stateOf(victim: PlayerId, nowTick: Long): FocusFireState?

    /**
     * Every currently-tracked window (across every victim). Used by
     * scheduled prune sweeps and by HUD update jobs.
     */
    public fun activeStates(nowTick: Long): Collection<FocusFireState>

    /**
     * Force-drop the window for [victim]. Used on victim death, match
     * end, mode switch.
     */
    public fun reset(victim: PlayerId)

    /**
     * Compute the bonus multiplier to apply to a hit from [attacker]
     * on [victim] at [atTick]. Returns `1.0` when no focus is active.
     * Centralised here so multiple callers (damage pipeline, advancement
     * trigger) get the same answer.
     */
    public fun bonusMultiplierFor(
        attacker: PlayerId,
        victim: PlayerId,
        atTick: Long,
    ): Double
}

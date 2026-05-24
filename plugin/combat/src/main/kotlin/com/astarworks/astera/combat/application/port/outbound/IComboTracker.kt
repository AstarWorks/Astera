package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.combo.ComboRoute
import com.astarworks.astera.combat.domain.combo.ComboRouteId
import com.astarworks.astera.combat.domain.combo.ComboState
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Outbound port: live combo state tracker (Wave 5combat-depth, ADR-0105).
 *
 * Owns the per-player combo timeline. Use cases call into this port to
 * record hits / skill casts; the tracker updates the [ComboState], evicts
 * expired states, evaluates registered routes, and publishes
 * `ComboStepRecorded` / `ComboRouteCompleted` events through
 * `IDomainEventBus`.
 *
 * # Why one port, not three (Recorder / Evaluator / Pruner)
 *
 * Splitting recording + evaluation + pruning across three ports would
 * leak the orchestration into callers — every use case would have to
 * sequence "record → maybe-prune → maybe-evaluate" itself. Keeping them
 * inside one port preserves the invariant that publishing happens
 * exactly once per state transition.
 *
 * # Why session-scoped (no persistence)
 *
 * Combos are gameplay-tactical, not progression-persistent. A combo
 * surviving a relog would let players bank chains across days and
 * trivialise finishers. Per ADR-0105 §"No persistence for combo /
 * focus-fire / target-lock", these tracker states do NOT mirror to
 * Postgres.
 */
public interface IComboTracker {

    /**
     * Register [route] so future combo states are evaluated against it.
     * Last write wins on duplicate [ComboRouteId] — content reload should
     * call [unregisterAll] first.
     */
    public fun registerRoute(route: ComboRoute)

    /** Drop every registered route. Used by content reload. */
    public fun unregisterAll()

    /** Look up a registered route by id, or null. */
    public fun routeOf(id: ComboRouteId): ComboRoute?

    /** Snapshot of every registered route. Stable iteration order. */
    public fun allRoutes(): Collection<ComboRoute>

    /**
     * Record a damage step in [playerId]'s combo against [victim], using
     * [weapon]. The tracker:
     *  1. Drops any existing state if the weapon changed or the previous
     *     state has expired by [atTick].
     *  2. Appends a `ComboStep.Hit(victim, damageDealt, atTick)`.
     *  3. Publishes `ComboStepRecorded`.
     *  4. Evaluates registered routes against the new state; if a route
     *     matches, publishes `ComboRouteCompleted` and resets the state.
     */
    public fun recordHit(
        playerId: PlayerId,
        weapon: ItemId,
        victim: PlayerId,
        damageDealt: Double,
        atTick: Long,
    )

    /**
     * Record a skill cast step. Mirror of [recordHit] for
     * `ComboStep.SkillCast`. Use this from `CastSkillUseCase` after the
     * cast actually succeeds (cooldown / mana checks already passed).
     */
    public fun recordSkillCast(
        playerId: PlayerId,
        weapon: ItemId,
        skill: SkillId,
        atTick: Long,
    )

    /**
     * Look up the live combo state for [playerId], or null if none /
     * expired (callers should pass the current tick so the tracker can
     * evict in-line).
     */
    public fun stateOf(playerId: PlayerId, nowTick: Long): ComboState?

    /**
     * Force-drop [playerId]'s combo state. Used on death, weapon-drop,
     * match end, mode switch.
     */
    public fun reset(playerId: PlayerId)
}

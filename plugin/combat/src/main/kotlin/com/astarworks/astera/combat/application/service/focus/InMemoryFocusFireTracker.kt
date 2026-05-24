package com.astarworks.astera.combat.application.service.focus

import com.astarworks.astera.combat.application.port.outbound.IFocusFireTracker
import com.astarworks.astera.combat.domain.event.FocusFireActivated
import com.astarworks.astera.combat.domain.event.FocusFireDeactivated
import com.astarworks.astera.combat.domain.focus.FocusFireConfig
import com.astarworks.astera.combat.domain.focus.FocusFireContribution
import com.astarworks.astera.combat.domain.focus.FocusFireState
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Default in-memory [IFocusFireTracker] implementation (Wave 5combat-depth,
 * ADR-0105). Not thread-safe; main-thread only.
 */
public class InMemoryFocusFireTracker(
    private val eventBus: IDomainEventBus,
    initialConfig: FocusFireConfig = FocusFireConfig.DEFAULT,
) : IFocusFireTracker {

    private var cfg: FocusFireConfig = initialConfig

    /** victim → state. */
    private val states: MutableMap<PlayerId, FocusFireState> = HashMap()

    /** victim → whether the window is currently active (edge detection). */
    private val activeFlags: MutableMap<PlayerId, Boolean> = HashMap()

    override fun configure(config: FocusFireConfig) {
        this.cfg = config
    }

    override fun config(): FocusFireConfig = cfg

    override fun recordHit(
        attacker: PlayerId,
        victim: PlayerId,
        damageDealt: Double,
        atTick: Long,
    ) {
        require(attacker != victim) {
            "InMemoryFocusFireTracker.recordHit: attacker ($attacker) and victim ($victim) must differ"
        }
        require(damageDealt.isFinite() && damageDealt >= 0.0) {
            "InMemoryFocusFireTracker.recordHit.damageDealt must be finite and >= 0: $damageDealt"
        }

        val pruned = pruneAndUpdate(victim = victim, attacker = attacker, damage = damageDealt, atTick = atTick)
        states[victim] = pruned
        evaluateActivation(victim, atTick)
    }

    override fun stateOf(victim: PlayerId, nowTick: Long): FocusFireState? {
        val cur = states[victim] ?: return null
        val pruned = pruneContributions(cur, nowTick)
        return if (pruned == null) {
            // No contributions left — deactivate if still flagged.
            if (activeFlags.remove(victim) == true) {
                eventBus.publish(
                    FocusFireDeactivated(
                        victim = victim,
                        durationTicks = (nowTick - cur.windowStartedAtTick).coerceAtLeast(0L).toInt(),
                        atTick = nowTick,
                    ),
                )
            }
            states.remove(victim)
            null
        } else {
            states[victim] = pruned
            pruned
        }
    }

    override fun activeStates(nowTick: Long): Collection<FocusFireState> {
        val victims = states.keys.toList()
        val active = mutableListOf<FocusFireState>()
        for (v in victims) {
            val s = stateOf(v, nowTick)
            if (s != null && s.isActive(cfg, nowTick)) active += s
        }
        return active
    }

    override fun reset(victim: PlayerId) {
        states.remove(victim)
        activeFlags.remove(victim)
    }

    override fun bonusMultiplierFor(
        attacker: PlayerId,
        victim: PlayerId,
        atTick: Long,
    ): Double {
        if (attacker == victim) return 1.0
        val s = stateOf(victim, atTick) ?: return 1.0
        if (!s.isActive(cfg, atTick)) return 1.0
        // Only contributors get the bonus — a drive-by hit by a non-contributor
        // doesn't benefit from focus.
        val contributes = s.contributions.any {
            it.attacker == attacker && it.lastHitAtTick >= atTick - cfg.windowTicks
        }
        return if (contributes) cfg.bonusMultiplier else 1.0
    }

    // ─── internals ──────────────────────────────────────────────────────────

    private fun pruneAndUpdate(
        victim: PlayerId,
        attacker: PlayerId,
        damage: Double,
        atTick: Long,
    ): FocusFireState {
        val cur = states[victim]
        val cutoff = atTick - cfg.windowTicks

        val baseContribs = cur?.contributions
            ?.filter { it.lastHitAtTick >= cutoff }
            ?.toMutableList()
            ?: mutableListOf()

        val idx = baseContribs.indexOfFirst { it.attacker == attacker }
        if (idx >= 0) {
            val prev = baseContribs[idx]
            baseContribs[idx] = prev.copy(
                totalDamage = prev.totalDamage + damage,
                hitCount = prev.hitCount + 1,
                lastHitAtTick = atTick,
            )
        } else {
            baseContribs += FocusFireContribution(
                attacker = attacker,
                totalDamage = damage,
                hitCount = 1,
                lastHitAtTick = atTick,
            )
        }

        // windowStartedAtTick is the earliest contribution's lastHitAtTick
        // (after pruning). Stays close to "when the focus began".
        val windowStart = baseContribs.minOf { it.lastHitAtTick }
        return FocusFireState(
            victim = victim,
            contributions = baseContribs.toList(),
            windowStartedAtTick = windowStart,
        )
    }

    /** Returns the state with stale contributions removed, or null if empty. */
    private fun pruneContributions(state: FocusFireState, nowTick: Long): FocusFireState? {
        val cutoff = nowTick - cfg.windowTicks
        val live = state.contributions.filter { it.lastHitAtTick >= cutoff }
        if (live.isEmpty()) return null
        val windowStart = live.minOf { it.lastHitAtTick }
        return FocusFireState(
            victim = state.victim,
            contributions = live,
            windowStartedAtTick = windowStart,
        )
    }

    private fun evaluateActivation(victim: PlayerId, atTick: Long) {
        val s = states[victim] ?: return
        val active = s.isActive(cfg, atTick)
        val wasActive = activeFlags[victim] == true
        when {
            active && !wasActive -> {
                activeFlags[victim] = true
                eventBus.publish(
                    FocusFireActivated(
                        victim = victim,
                        attackers = s.contributions
                            .filter { it.lastHitAtTick >= atTick - cfg.windowTicks }
                            .map { it.attacker },
                        totalDamage = s.totalDamage,
                        atTick = atTick,
                    ),
                )
            }
            !active && wasActive -> {
                activeFlags.remove(victim)
                eventBus.publish(
                    FocusFireDeactivated(
                        victim = victim,
                        durationTicks = (atTick - s.windowStartedAtTick)
                            .coerceAtLeast(0L)
                            .toInt(),
                        atTick = atTick,
                    ),
                )
            }
            else -> Unit
        }
    }
}

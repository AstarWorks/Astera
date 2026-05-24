package com.astarworks.astera.combat.application.service.combo

import com.astarworks.astera.combat.application.port.outbound.IComboTracker
import com.astarworks.astera.combat.domain.combo.ComboMatcher
import com.astarworks.astera.combat.domain.combo.ComboRoute
import com.astarworks.astera.combat.domain.combo.ComboRouteId
import com.astarworks.astera.combat.domain.combo.ComboState
import com.astarworks.astera.combat.domain.combo.ComboStep
import com.astarworks.astera.combat.domain.event.ComboRouteCompleted
import com.astarworks.astera.combat.domain.event.ComboStepRecorded
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId

/**
 * Default in-memory [IComboTracker] implementation (Wave 5combat-depth,
 * ADR-0105). Holds per-player state in plain maps; not thread-safe
 * because Astera dispatches on the Paper main thread (same convention
 * as `InMemoryDomainEventBus`, `InMemoryManaPoolService`).
 *
 * Wave 6 may overlay a Paper-side scheduler that prunes expired states
 * on a 20-tick timer; that's adapter-layer optimisation and does not
 * change this port.
 */
public class InMemoryComboTracker(
    private val eventBus: IDomainEventBus,
) : IComboTracker {

    private val routes: MutableMap<ComboRouteId, ComboRoute> = LinkedHashMap()
    private val states: MutableMap<PlayerId, ComboState> = HashMap()

    override fun registerRoute(route: ComboRoute) {
        routes[route.id] = route
    }

    override fun unregisterAll() {
        routes.clear()
    }

    override fun routeOf(id: ComboRouteId): ComboRoute? = routes[id]

    override fun allRoutes(): Collection<ComboRoute> = routes.values.toList()

    override fun recordHit(
        playerId: PlayerId,
        weapon: ItemId,
        victim: PlayerId,
        damageDealt: Double,
        atTick: Long,
    ) {
        appendStep(
            playerId = playerId,
            weapon = weapon,
            step = ComboStep.Hit(victim = victim, damageDealt = damageDealt, atTick = atTick),
            atTick = atTick,
        )
    }

    override fun recordSkillCast(
        playerId: PlayerId,
        weapon: ItemId,
        skill: SkillId,
        atTick: Long,
    ) {
        appendStep(
            playerId = playerId,
            weapon = weapon,
            step = ComboStep.SkillCast(skill = skill, atTick = atTick),
            atTick = atTick,
        )
    }

    override fun stateOf(playerId: PlayerId, nowTick: Long): ComboState? {
        val cur = states[playerId] ?: return null
        if (cur.isExpiredAt(nowTick)) {
            states.remove(playerId)
            return null
        }
        return cur
    }

    override fun reset(playerId: PlayerId) {
        states.remove(playerId)
    }

    private fun appendStep(
        playerId: PlayerId,
        weapon: ItemId,
        step: ComboStep,
        atTick: Long,
    ) {
        // Drop the existing state if the weapon changed or it expired.
        val existing = states[playerId]?.takeIf { prev ->
            prev.weaponId == weapon && !prev.isExpiredAt(atTick)
        }

        val nextSteps = (existing?.steps ?: emptyList()) + step
        val next = ComboState(
            playerId = playerId,
            weaponId = weapon,
            steps = nextSteps,
            lastStepAtTick = atTick,
        )
        states[playerId] = next

        eventBus.publish(
            ComboStepRecorded(
                playerId = playerId,
                weaponId = weapon,
                step = step,
                streakCount = next.streakCount,
                atTick = atTick,
            ),
        )

        // Evaluate registered routes against the new state — first match wins,
        // state resets so the player has to rebuild the chain.
        val matched = routes.values.firstOrNull { ComboMatcher.matches(next, it) }
        if (matched != null) {
            states.remove(playerId)
            eventBus.publish(
                ComboRouteCompleted(
                    playerId = playerId,
                    weaponId = weapon,
                    routeId = matched.id,
                    skill = matched.finisher,
                    bonusDamageMultiplier = matched.bonusDamageMultiplier,
                    streakCount = next.streakCount,
                    atTick = atTick,
                ),
            )
        }
    }
}

package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.condition.Condition
import com.astarworks.astera.core.domain.skill.value.NumericValue

/**
 * Evaluates the entity-state [Condition]s (WP4): HasStatus, IsAlly, IsEnemy,
 * HealthBelow, DistanceLessThan. Targeters are resolved through
 * [TargeterResolver]; every condition is false when its targeter(s) resolve to
 * nothing, and quantifies over *all* resolved targets (`a` x `b` for the
 * two-sided ones). Works for players and mobs alike.
 *
 * - Ally: same entity, or both on the same known team.
 * - Enemy: different entities that are not known teammates (a target without a
 *   team is an enemy of everyone), so IsAlly and IsEnemy are exact complements
 *   for distinct entities.
 */
internal class ConditionEvaluator(
    private val targeters: TargeterResolver,
    private val teamLookup: IMatchTeamLookup?,
    private val statusContainer: IStatusEffectContainer?,
    private val vitals: TargetVitals?,
    private val resolveDouble: (NumericValue, MechanicContext) -> Double,
) {
    fun evaluate(condition: Condition, ctx: MechanicContext): Boolean? = when (condition) {
        is Condition.HasStatus -> {
            val targets = targeters.resolvePlayers(condition.targets, ctx)
            statusContainer != null && targets.isNotEmpty() &&
                targets.all {
                    if (condition.minStacks <= 1) statusContainer.has(it, condition.statusId)
                    else statusContainer.stacks(it, condition.statusId) >= condition.minStacks
                }
        }
        is Condition.IsAlly -> pairwise(condition.a, condition.b, ctx, ::areAllies)
        is Condition.IsEnemy -> pairwise(condition.a, condition.b, ctx) { x, y -> !areAllies(x, y) }
        is Condition.HealthBelow -> {
            val targets = targeters.resolvePlayers(condition.targets, ctx)
            val threshold = resolveDouble(condition.fraction, ctx)
            vitals != null && targets.isNotEmpty() &&
                targets.all { id -> vitals.fraction(id)?.let { it < threshold } == true }
        }
        is Condition.DistanceLessThan -> {
            val a = targeters.resolveLocations(condition.a, ctx).firstOrNull()
            val b = targeters.resolveLocations(condition.b, ctx).firstOrNull()
            a != null && b != null && a.distance(b) < resolveDouble(condition.distance, ctx)
        }
        else -> null
    }

    private fun pairwise(
        a: com.astarworks.astera.core.domain.skill.targeter.Targeter,
        b: com.astarworks.astera.core.domain.skill.targeter.Targeter,
        ctx: MechanicContext,
        test: (PlayerId, PlayerId) -> Boolean,
    ): Boolean {
        val left = targeters.resolvePlayers(a, ctx)
        val right = targeters.resolvePlayers(b, ctx)
        if (left.isEmpty() || right.isEmpty()) return false
        return left.all { x -> right.all { y -> test(x, y) } }
    }

    private fun areAllies(x: PlayerId, y: PlayerId): Boolean {
        if (x == y) return true
        val teams = teamLookup ?: return false
        val tx = teams.teamOf(x) ?: return false
        return tx == teams.teamOf(y)
    }
}

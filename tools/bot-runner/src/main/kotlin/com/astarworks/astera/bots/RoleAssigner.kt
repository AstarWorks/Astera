package com.astarworks.astera.bots

import kotlin.math.hypot

/** One bot's job: the [role], plus the stage region it works at ([zone]) or the player it works against ([subject]). */
data class Assignment(val role: Role, val zone: String? = null, val subject: String? = null)

/**
 * Team roles per mode. Pure and deterministic: every bot of a team runs this on the same roster (sorted by name) and the
 * same [ModeFacts], so the team splits its jobs without talking, and the split is recomputed each call: when the carrier
 * dies, a zone is lost, or the barrier opens, the next call hands out different roles.
 */
class RoleAssigner(
    private val own: String,
    private val self: String,
    mates: List<Mate>,
) {
    /** Living team mates (self included even when the Tab list does not show it), sorted by name. */
    private val alive: List<Mate> =
        (mates.filter { it.name != self && it.alive } + (mates.firstOrNull { it.name == self } ?: Mate(self, Double.NaN, Double.NaN)))
            .sortedBy { it.name }
    private val n = alive.size
    private val index = alive.indexOfFirst { it.name == self }

    private fun nearestMate(x: Double, z: Double): Mate? =
        alive.filter { it.known() }.minByOrNull { hypot(it.x - x, it.z - z) } ?: alive.firstOrNull()

    /** [list] rotated so the mate at [index] (among [others]) picks element `k % size`: a stable round robin. */
    private fun <T> pick(list: List<T>, k: Int): T = list[Math.floorMod(k, list.size)]

    // ---- carry-tnt -------------------------------------------------------------------------------------------------

    /**
     * - own team has the TNT: the carrier carries; the others escort; the last one of a squad of 3+ guards the own goal.
     * - the enemy has it: everyone intercepts the carrier, except the last of a squad of 3+ who guards the own goal.
     * - on the ground behind a shield: half the squad (rounded up) breaks the shield, the rest guards the own goal.
     * - on the ground with no shield: the mate nearest to the TNT takes it, the rest escort (last one guards the goal).
     */
    fun carryTnt(facts: ModeFacts, selfHolds: Boolean, tntX: Double, tntZ: Double): Assignment {
        if (selfHolds) return Assignment(Role.CARRIER)
        val tnt = facts.tnt()
        when {
            tnt?.carrierTeam == own -> {
                val carrier = tnt.carrierName ?: nearestMate(tnt.x, tnt.z)?.name
                if (carrier == self) return Assignment(Role.CARRIER)
                val rest = alive.filter { it.name != carrier }
                val k = rest.indexOfFirst { it.name == self }
                return if (n >= 3 && k == rest.lastIndex) Assignment(Role.GOAL_GUARD) else Assignment(Role.ESCORT, subject = carrier)
            }
            tnt?.carrierTeam != null -> {
                return if (n >= 3 && index == n - 1) Assignment(Role.GOAL_GUARD) else Assignment(Role.INTERCEPTOR)
            }
        }
        val shieldUp = facts.shieldHp()?.let { it > 0.0 } ?: false
        if (shieldUp) {
            val breakers = (n + 1) / 2
            return if (index < breakers) Assignment(Role.SHIELD_BREAKER) else Assignment(Role.GOAL_GUARD)
        }
        val taker = nearestMate(tntX, tntZ)?.name
        if (taker == self) return Assignment(Role.CARRIER)
        val rest = alive.filter { it.name != taker }
        val k = rest.indexOfFirst { it.name == self }
        return if (n >= 3 && k == rest.lastIndex) Assignment(Role.GOAL_GUARD) else Assignment(Role.ESCORT, subject = taker)
    }

    // ---- capture-wool ----------------------------------------------------------------------------------------------

    /**
     * Zones the team owns get a holder each (at most one per three mates), everyone else works the zones it does not own:
     * a zone the team held a moment ago is retaken, any other is contested. With every zone held, the squad spreads out over them.
     */
    fun captureWool(zones: List<String>, facts: ModeFacts, lostBefore: Set<String>): Assignment? {
        if (zones.isEmpty()) return null
        val sorted = zones.sorted()
        val mine = sorted.filter { facts.zoneOwner(it) == own }
        val open = sorted.filter { facts.zoneOwner(it) != own }
        if (open.isEmpty()) return Assignment(Role.HOLDER, zone = pick(sorted, index))
        val holders = minOf(mine.size, maxOf(1, n / 3)).takeIf { mine.isNotEmpty() } ?: 0
        if (index < holders) return Assignment(Role.HOLDER, zone = pick(mine, index))
        val zone = pick(open, index - holders)
        return Assignment(if (zone in lostBefore) Role.RETAKER else Role.CONTESTER, zone = zone)
    }

    // ---- carry-mini-tnt --------------------------------------------------------------------------------------------

    /**
     * - holder of the power TNT throws it; with the barrier of the enemy basket open, a mate carrying clams rushes them in.
     * - the last mate of a squad of 3+ guards the own basket (a second one while the enemy holds a power TNT).
     * - everybody else collects.
     */
    fun carryMiniTnt(facts: ModeFacts, enemyTeams: List<String>, carried: Int, holdsPower: Boolean): Assignment {
        val power = facts.powerTnt()
        if (holdsPower || (power?.team == own && power.holder == self)) return Assignment(Role.THROWER)
        val enemyPower = power != null && power.team != own
        val guards = if (n >= 3) (if (enemyPower) 2 else 1) else if (enemyPower && n == 2) 1 else 0
        if (index >= n - guards) return Assignment(Role.BASKET_GUARD, subject = power?.takeIf { enemyPower }?.holder)
        val open = enemyTeams.any { facts.barrierOpen(it) == true }
        if (open && carried > 0) return Assignment(Role.RUSHER)
        return Assignment(Role.COLLECTOR)
    }

    // ---- steal-jewelry ---------------------------------------------------------------------------------------------

    /** One guard per three mates (at least one in a squad of 2+), and one more while an enemy is seen near the own pedestal. */
    fun stealJewelry(threatened: Boolean): Assignment {
        val guards = (if (n >= 2) maxOf(1, n / 3) else 0) + (if (threatened && n >= 2) 1 else 0)
        return if (index >= n - minOf(guards, n - 1)) Assignment(Role.PEDESTAL_GUARD) else Assignment(Role.THIEF)
    }

    // ---- annihilation ----------------------------------------------------------------------------------------------

    /** Defenders at the highest names (one per squad of 3+, two once the own base is threatened and the squad has 4+); the rest attack. */
    fun annihilation(threatened: Boolean): Assignment {
        val defenders = if (n >= 3) (if (threatened && n >= 4) 2 else 1) else 0
        return if (index >= n - defenders) Assignment(Role.PILLAR_DEFENDER) else Assignment(Role.PILLAR_ATTACKER)
    }

    companion object {
        /** The enemy team to burn first: the one with the fewest pillars standing (unknown counts as all three), ties by name. */
        fun weakestTeam(enemyTeams: List<String>, standing: (String) -> Int): String? =
            enemyTeams.sorted().filter { standing(it) > 0 }.minByOrNull { standing(it) }
    }
}

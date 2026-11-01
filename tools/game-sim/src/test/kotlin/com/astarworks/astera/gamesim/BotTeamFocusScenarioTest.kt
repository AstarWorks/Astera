package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.Mate
import com.astarworks.astera.bots.ModeFacts
import com.astarworks.astera.bots.ModePlanner
import com.astarworks.astera.bots.RoleAssigner
import com.astarworks.astera.bots.Role
import com.astarworks.astera.bots.Sighting
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.bots.TieBreak
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Annihilation bots pick the enemy team to burn without favouring a name: ties go to the team that hurt us last, then to a
 * seeded draw (match key + bot / squad), never to the alphabetical first; from phase 3 the whole squad commits to one team (the
 * weakest, ties by the squad-shared seed) and its Nexus; eliminated teams are never targeted. Driven against the real
 * `annihilation` stage, the way [BotModeRolesScenarioTest] drives the planner.
 */
class BotTeamFocusScenarioTest {
    private class Facts : ModeFacts {
        var phase: Int? = 2
        val gone = HashSet<Pair<String, Int>>()
        var seen: List<Sighting> = emptyList()
        override fun phase() = phase
        override fun pillarStanding(team: String, tier: Int) = (team to tier) !in gone
        override fun enemies() = seen
    }

    private val src = checkNotNull(StageObjectives.load(GameSim.defaultContentDir().toFile(), "annihilation")) { "annihilation stage did not load" }
    private val zones = checkNotNull(src.zones())
    private val spawn = checkNotNull(zones.point("center"))
    private val squad = (1..6).map { Mate("r$it", spawn.first, spawn.third) }

    private fun planner(name: String, facts: Facts, enemies: List<String> = listOf("blue", "yellow"), key: String = "m0") =
        ModePlanner("annihilation", name, "red", enemies, zones, facts = facts, matchKey = key).also { it.noteRoster(squad) }

    /** The zone of an attack step (`pillar-<team>-<tier>`); a defender's guard step or no step is null. */
    private fun attackZone(p: ModePlanner): List<String>? =
        p.step(0, spawn.first, spawn.third)?.takeIf { it.kind == "pillar" }?.zone?.split('-')

    private fun targetOf(p: ModePlanner): String? = attackZone(p)?.get(1)

    private fun tierOf(p: ModePlanner): Int? = attackZone(p)?.get(2)?.toInt()

    @Test
    fun `a tie between enemies is not decided by name or list order`() {
        val facts = Facts()
        val keys = (0 until 60).map { "match-$it" }
        val picks = keys.map { k -> targetOf(planner("r1", facts, key = k)) }
        // Neither the alphabetical first (blue) nor the last (yellow) takes the tie systematically.
        assertThat(picks.count { it == "blue" }).isBetween(15, 45)
        assertThat(picks.count { it == "yellow" }).isBetween(15, 45)
        // The order the enemies are listed in does not matter: the same key picks the same team.
        for (k in keys) assertThat(targetOf(planner("r1", facts, listOf("yellow", "blue"), k))).isEqualTo(targetOf(planner("r1", facts, key = k)))
        // The draw is deterministic: the same key and bot name pick the same team every time.
        assertThat(keys.map { k -> targetOf(planner("r1", facts, key = k)) }).isEqualTo(picks)
        // Different bots of one squad draw on their own (phase 2: they may split).
        assertThat((1..6).map { targetOf(planner("r$it", facts, key = "match-1")) }.toSet()).hasSizeGreaterThanOrEqualTo(1)

        // Swapped names: the pure rule gives the same pick for a team called "a" or "z" (the name is not an input).
        fun pure(teams: List<String>, seed: Long) = RoleAssigner.weakestTeam(teams, { 3 }, priority = { t -> TieBreak.priority(seed, t) })
        val byName = (0L until 40L).map { pure(listOf("a", "z"), it) }
        assertThat(byName).contains("a", "z")
        assertThat((0L until 40L).map { pure(listOf("z", "a"), it) }).isEqualTo(byName)
        // Renaming the teams only changes which seeded number each one gets, not the rule: the shares stay balanced.
        val renamed = (0L until 200L).map { pure(listOf("aardvark", "zebra"), it) }
        assertThat(renamed.count { it == "aardvark" }).isBetween(70, 130)
    }

    @Test
    fun `the team that hurt us last wins a tie`() {
        val facts = Facts()
        for (k in (0 until 20).map { "match-$it" }) {
            val p = planner("r1", facts, key = k)
            p.noteAttackerTeam(5_000, "yellow")
            p.noteAttackerTeam(9_000, "blue")
            assertThat(targetOf(p)).isEqualTo("blue")
            val q = planner("r1", facts, key = k)
            q.noteAttackerTeam(9_000, "yellow")
            q.noteAttackerTeam(5_000, "blue")
            assertThat(targetOf(q)).isEqualTo("yellow")
        }
    }

    @Test
    fun `from phase 3 every attacker of the squad commits to the same weakened enemy and its Nexus`() {
        val facts = Facts().apply { phase = 3 }
        // A tie: all six bots draw on the squad-shared seed, so they agree, even when each was hurt by someone else.
        for (k in (0 until 20).map { "match-$it" }) {
            val planners = squad.map { planner(it.name, facts, key = k) }
            planners.forEachIndexed { i, p -> p.noteAttackerTeam(1_000L + i, if (i % 2 == 0) "blue" else "yellow") }
            assertThat(planners.map { targetOf(it) }.toSet()).hasSize(1)
            assertThat(planners.map { tierOf(it) }.toSet()).containsExactly(3)
            assertThat(planners.map { it.role }.toSet()).containsExactly(Role.PILLAR_ATTACKER)
        }
        // Yellow lost its tier 1: it is the weakest, everybody goes for yellow's Nexus.
        facts.gone += "yellow" to 1
        val weak = squad.map { planner(it.name, facts) }
        assertThat(weak.map { targetOf(it) }.toSet()).containsExactly("yellow")
        assertThat(weak.map { tierOf(it) }.toSet()).containsExactly(3)
        assertThat(weak.first().focusTeam).isEqualTo("yellow")

        // Defenders stay home only while the own pillar is threatened: an enemy at the red tier 1 pulls the usual defender back.
        val redPillar = zones.pillars().first { it.team == "red" }
        facts.seen = listOf(Sighting("b1", redPillar.x + 0.5, redPillar.z + 0.5))
        val threatened = squad.map { planner(it.name, facts) }.onEach { it.step(0, spawn.first, spawn.third) }
        assertThat(threatened.count { it.role == Role.PILLAR_DEFENDER }).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `an eliminated team is never targeted`() {
        for (phase in listOf(2, 3)) {
            val facts = Facts().apply { this.phase = phase }
            // Yellow's Nexus is gone (eliminated) although its tier 1 and 2 still stand: only blue is left to attack.
            facts.gone += "yellow" to 3
            for (k in (0 until 20).map { "match-$it" }) {
                assertThat(squad.mapNotNull { targetOf(planner(it.name, facts, key = k)) }.toSet()).containsExactly("blue")
            }
            // Blue has no pillar left either: nobody to attack.
            facts.gone += listOf("blue" to 1, "blue" to 2, "blue" to 3)
            assertThat(squad.mapNotNull { targetOf(planner(it.name, facts)) }).isEmpty()
        }
    }
}

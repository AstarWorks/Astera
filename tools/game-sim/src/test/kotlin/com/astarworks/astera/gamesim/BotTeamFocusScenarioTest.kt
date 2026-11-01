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

    /** The objective zone of an attack step (`pillar-<team>-<tier>`; kind pillar, or rally / detour on the way); a defender's guard step or no step is null. */
    private fun attackZone(p: ModePlanner): List<String>? =
        p.step(0, spawn.first, spawn.third)?.takeIf { it.kind in setOf("pillar", "rally", "detour") }?.zone?.split('-')

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
    fun `a tied pick is stable across consecutive ticks`() {
        for (phase in listOf(2, 3)) {
            val facts = Facts().apply { this.phase = phase }
            for (k in (0 until 20).map { "match-$it" }) {
                val p = planner("r1", facts, key = k)
                val ticks = (0 until 50).map { targetOf(p) }
                assertThat(ticks.toSet()).hasSize(1)
                // A fresh planner (e.g. after a respawn) with the same key lands on the same team: nothing time-dependent.
                assertThat(targetOf(planner("r1", facts, key = k))).isEqualTo(ticks.first())
            }
        }
        // Same for the per-entity tie of the target selector: identical scores, 50 ticks, one pick.
        val cands = (1..4).map { com.astarworks.astera.bots.TargetPolicy.Candidate(it, "e$it", "blue", false, 10.0, 64.0, 10.0) }
        val sel = com.astarworks.astera.bots.TargetSelector().apply { seedTies(com.astarworks.astera.bots.TieBreak.seed("m", "r1")) }
        val picks = (0 until 50).map { sel.select(it * 50L, "r1", "red", 0.0, 64.0, 0.0, cands).target?.id }
        assertThat(picks.toSet()).hasSize(1)
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
    fun `attackers do not camp an enemy spawn zone but fight defenders that leave it or hit them`() {
        val facts = Facts().apply { phase = 3 }
        val blueSpawns = zones.spawns().filter { it.first == "blue" }
        assertThat(blueSpawns).isNotEmpty()
        val sx = blueSpawns.map { it.second }.average()
        val sz = blueSpawns.map { it.third }.average()
        fun fresh() = ModePlanner("annihilation", "r1", "red", listOf("blue", "yellow"), zones, facts = facts, matchKey = "m0", teamOf = { "blue" })
            .also { it.noteRoster(squad); it.step(0, sx, sz) }
        val inside = Sighting("b1", sx + 1.0, sz + 1.0)
        // Standing in the blue spawn zone with a blue defender next to us: not engaged (pillar first).
        assertThat(inside.let { fresh().engages(it, 1_000, sx, sz) }).isFalse()
        // Hit inside the zone: still not engaged (it leaves instead of fighting back); the step goes outward past the zone edge.
        val hurt = fresh().also { it.noteDamaged(900) }
        assertThat(hurt.engages(inside, 1_000, sx, sz)).isFalse()
        val out = checkNotNull(hurt.zoneEscape(1_000, sx, sz))
        assertThat(out.kind).isEqualTo("leave-zone")
        assertThat(hurt.enemyZoneOf(out.x, out.z)).isNull()
        // Walking out step by step: never engaged on the way, no trap, and it ends outside every enemy zone.
        var x = sx; var z = sz; var t = 1_000L
        while (hurt.enemyZoneOf(x, z) != null && t < 20_000L) {
            assertThat(hurt.engages(Sighting("b1", x + 1.0, z), t, x, z)).isFalse()
            val st = checkNotNull(hurt.zoneEscape(t, x, z))
            val d = Math.hypot(st.x - x, st.z - z)
            x += (st.x - x) / d * 1.0; z += (st.z - z) / d * 1.0; t += 200L
        }
        assertThat(hurt.enemyZoneOf(x, z)).isNull()
        assertThat(hurt.zoneTrapped).isFalse()
        // Trapped (could not get out for ZONE_TRAP_MS): the only case it fights back.
        val trapped = ModePlanner("annihilation", "r1", "red", listOf("blue", "yellow"), zones, facts = facts, matchKey = "m0", teamOf = { n -> if (n == "nobody") "red" else "blue" })
            .also { it.noteRoster(squad); it.step(0, sx, sz); it.noteDamaged(1_000) }
        trapped.zoneEscape(1_000, sx, sz)
        assertThat(trapped.zoneEscape(1_000 + ModePlanner.ZONE_TRAP_MS + 1, sx, sz)).isNull()
        assertThat(trapped.zoneTrapped).isTrue()
        assertThat(trapped.engages(Sighting("nobody", sx + 1.0, sz), 1_000 + ModePlanner.ZONE_TRAP_MS + 2, sx, sz)).isTrue()
        // The same defender far outside the zone, within engage range of an attacker next to it: engaged.
        val ox = sx + 80.0
        assertThat(fresh().engages(Sighting("b1", ox, sz), 1_000, ox - 3.0, sz)).isTrue()
    }

    @Test
    fun `attackers rally outside the target spawn zone and push together`() {
        val facts = Facts().apply { phase = 3 }
        val start = checkNotNull(zones.spawns().firstOrNull { it.first == "red" })
        fun planner(mates: List<Mate>) = ModePlanner("annihilation", "r1", "red", listOf("blue", "yellow"), zones, facts = facts, matchKey = "m0")
            .also { it.noteRoster(mates) }
        val first = checkNotNull(planner(squad).step(0, start.second, start.third))
        assertThat(first.kind).isEqualTo("rally")
        val target = first.zone.split('-')[1]
        // The rally point lies outside every enemy spawn zone.
        assertThat(planner(squad).enemyZoneOf(first.x, first.z)).isNull()
        // Mates around the point: once the bot stands there with enough of them, it leaves the rally and heads for the pillar.
        val around = (1..2).map { Mate("r$it", first.x + it, first.z) }
        val p = planner(around)
        assertThat(p.step(0, start.second, start.third)?.kind).isEqualTo("rally")
        val go = checkNotNull(p.step(1_000, first.x, first.z))
        assertThat(go.kind).isIn("pillar", "detour")
        assertThat(go.zone).startsWith("pillar-$target-")
        // Nobody comes: the timeout releases the push (deterministic, driven by the passed clock).
        val lonely = planner(squad)
        assertThat(lonely.step(0, first.x, first.z)?.kind).isEqualTo("rally")
        assertThat(lonely.step(ModePlanner.RALLY_TIMEOUT_MS - 1, first.x, first.z)?.kind).isEqualTo("rally")
        assertThat(lonely.step(ModePlanner.RALLY_TIMEOUT_MS + 1, first.x, first.z)?.kind).isIn("pillar", "detour")
    }

    @Test
    fun `the way to a pillar does not run through an enemy spawn zone`() {
        val facts = Facts().apply { phase = 3 }
        facts.gone += "yellow" to 3
        val nexus = zones.pillars().first { it.team == "blue" && it.tier == 3 }
        val blue = zones.spawns().filter { it.first == "blue" }
        val cx = blue.map { it.second }.average(); val cz = blue.map { it.third }.average()
        // A bot on the far side of the spawn from the Nexus: the straight line crosses the zone, so the first step is a waypoint around it.
        val dx = cx - (nexus.x + 0.5); val dz = cz - (nexus.z + 0.5)
        val l = Math.hypot(dx, dz)
        val fx = cx + dx / l * 30.0; val fz = cz + dz / l * 30.0
        val p = ModePlanner("annihilation", "r1", "red", listOf("blue", "yellow"), zones, facts = facts, matchKey = "m0").also { it.noteRoster(listOf(Mate("r2", fx, fz))) }
        p.step(0, fx, fz)
        val st = checkNotNull(p.step(ModePlanner.RALLY_TIMEOUT_MS + 5, fx, fz))
        assertThat(st.kind).isEqualTo("detour")
        assertThat(p.enemyZoneOf(st.x, st.z)).isNull()
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

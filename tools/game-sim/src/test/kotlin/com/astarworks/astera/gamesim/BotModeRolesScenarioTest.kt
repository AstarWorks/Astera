package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.ClamFact
import com.astarworks.astera.bots.Mate
import com.astarworks.astera.bots.ModeFacts
import com.astarworks.astera.bots.ModePlanner
import com.astarworks.astera.bots.PillarHpFact
import com.astarworks.astera.bots.PlayerView
import com.astarworks.astera.bots.PowerTntFact
import com.astarworks.astera.bots.Role
import com.astarworks.astera.bots.Sighting
import com.astarworks.astera.bots.SnapshotModeFacts
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.bots.TntFact
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.domain.mode.GameModeId
import com.astarworks.astera.match.application.gamemode.siege.IObjectiveStateQuery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * BOT-04: bots play a ROLE per mode (item: team roles). The squad splits its jobs from the roster and the mode facts with no
 * talking, the split follows the situation, and the role's step is the mode's objective action. Planner state is driven the
 * same way [BotModeObjectiveScenarioTest] does (the sim player teleports to each step), against the real stages and modes.
 */
class BotModeRolesScenarioTest {

    /** Mode facts a scenario sets by hand; the manager wires the real query to the same interface. */
    private class Facts : ModeFacts {
        var tnt: TntFact? = null
        var shield: Double? = null
        val owners = HashMap<String, String?>()
        var barrier = HashMap<String, Boolean>()
        var power: PowerTntFact? = null
        var clamList: List<ClamFact>? = null
        val gone = HashSet<Pair<String, Int>>()
        var seen: List<Sighting> = emptyList()
        val pillarHps = HashMap<Int, PillarHpFact>()
        /** annihilation phase on the boss bar; 2 = pillars open, so tests that are not about Preparation see the usual plan. */
        var phase: Int? = 2
        override fun phase() = phase
        override fun tnt() = tnt
        override fun shieldHp() = shield
        override fun zoneOwner(zone: String) = owners[zone]
        override fun clams() = clamList
        override fun barrierOpen(team: String) = barrier[team]
        override fun powerTnt() = power
        override fun pillarStanding(team: String, tier: Int) = (team to tier) !in gone
        override fun enemies() = seen
        override fun pillarHp(team: String, tier: Int) = pillarHps[tier]
    }

    private fun objectives(lane: String): StageObjectives =
        checkNotNull(StageObjectives.load(GameSim.defaultContentDir().toFile(), lane)) { "stage of lane $lane did not load" }

    private fun squad(prefix: String, at: Triple<Double, Double, Double>, count: Int = 4) =
        (1..count).map { Mate("$prefix$it", at.first, at.third) }

    private fun planner(mode: String, src: StageObjectives, name: String, team: String, enemies: List<String>, facts: ModeFacts, roster: List<Mate>) =
        ModePlanner(mode, name, team, enemies, checkNotNull(src.zones()), facts = facts).also { it.noteRoster(roster) }

    /** Role every mate of [names] takes at the mate's own position. */
    private fun rolesOf(names: List<String>, make: (String) -> ModePlanner, at: (String) -> Triple<Double, Double, Double>): Map<String, Role?> =
        names.associateWith { n -> make(n).also { p -> at(n).let { p.step(0, it.first, it.third) } }.role }

    // ---- carry-tnt --------------------------------------------------------------------------------------------------

    @Test
    fun `carry-tnt - shield breakers, then a carrier with escorts, and the enemy intercepts its walk to the goal`() {
        scenario("bot-roles-carry-tnt") {
            val runner = player("r1", at = Vec3(0.0, 65.0, -50.0))
            val enemy = player("b1", at = Vec3(0.0, 65.0, -50.0))
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", enemy to "blue"), stage = "siege-isles-tnt")
            val src = objectives("carry-tnt")
            val zones = checkNotNull(src.zones())
            val redBase = checkNotNull(zones.point("base-red"))
            val blueBase = checkNotNull(zones.point("base-blue"))
            val red = squad("r", redBase)
            val blue = squad("b", blueBase)
            val facts = Facts()
            fun redPlanner(n: String) = planner("carry-tnt", src, n, "red", listOf("blue"), facts, red)
            val names = red.map { it.name }

            // The TNT is shielded: half the squad stands in tnt-spawn and breaks it, the rest keep the own goal.
            facts.shield = 200.0
            val shielded = rolesOf(names, ::redPlanner) { redBase }
            assertThat(shielded).containsEntry("r1", Role.SHIELD_BREAKER).containsEntry("r2", Role.SHIELD_BREAKER)
                .containsEntry("r3", Role.GOAL_GUARD).containsEntry("r4", Role.GOAL_GUARD)
            assertThat(redPlanner("r1").step(0, redBase.first, redBase.third)?.kind).isEqualTo("shield")

            // Shield broken: one carrier takes the TNT, two escort, one keeps the goal.
            facts.shield = 0.0
            val open = rolesOf(names, ::redPlanner) { redBase }
            assertThat(open).containsEntry("r1", Role.CARRIER).containsEntry("r2", Role.ESCORT)
                .containsEntry("r3", Role.ESCORT).containsEntry("r4", Role.GOAL_GUARD)

            // r1 plays the carrier in the real mode: takes the TNT, walks to the enemy goal.
            val carrier = redPlanner("r1")
            val kinds = ArrayList<String>()
            var now = 0L
            var held = 0
            var confirmed = 0
            var interceptStep: ModePlanner.Step? = null
            var escortStep: ModePlanner.Step? = null
            // Shield break (~20 s), two checkpoint holds (~4 s each), the deliver hold (~3 s) and the walks: 60 s is plenty, the loop ends at MatchEnded.
            repeat(120) {
                if (eventsOf<MatchEnded>().isNotEmpty()) return@repeat
                val have = sim.objectiveStage.carriedCount(runner.id)
                if (have > held) confirmed += have - held
                held = have
                carrier.noteConfirmed(confirmed)
                val step = checkNotNull(carrier.step(now, runner.position.x, runner.position.z))
                if (kinds.lastOrNull() != step.kind) kinds += step.kind
                runner.moveTo(Vec3(step.x, 65.0, step.z))
                if (carrier.clickWanted) clickNearestMarker(runner, matchId)
                if (carrier.holdingTnt) {
                    // The facts now say red carries; the mates see r1 where it stands.
                    facts.tnt = TntFact(runner.position.x, runner.position.z, "red", "r1")
                    val roster = listOf(Mate("r1", runner.position.x, runner.position.z)) + red.drop(1)
                    val r2 = planner("carry-tnt", src, "r2", "red", listOf("blue"), facts, roster)
                    escortStep = r2.step(now, redBase.first, redBase.third)
                    interceptStep = planner("carry-tnt", src, "b1", "blue", listOf("red"), facts, blue).step(now, blueBase.first, blueBase.third)
                }
                advance(10)
                now += 500
            }
            // siege-isles-tnt has checkpoints: the carrier secures them (route kind "checkpoint") before the final "deliver" leg.
            assertThat(kinds.take(2)).containsExactly("tnt", "checkpoint")
            assertThat(carrier.role).isEqualTo(Role.CARRIER)
            assertThat(escortStep?.role).isEqualTo(Role.ESCORT)
            assertThat(escortStep?.kind).isEqualTo("escort")
            assertThat(interceptStep?.role).`as`("the enemy squad goes for the carrier").isEqualTo(Role.INTERCEPTOR)
            assertThat(interceptStep?.kind).isEqualTo("intercept")
            assertThat(eventsOf<MatchEnded>().singleOrNull()?.winningTeam).isEqualTo(TeamId("red"))
        }
    }

    /** The live bot sees only boss bars and a glowing player: the same roles must come out of what the client observes. */
    @Test
    fun `carry-tnt observed from the boss bars - the shield breaks first, then the enemy intercepts and the own team escorts with no second carrier`() {
        val src = objectives("carry-tnt")
        val zones = checkNotNull(src.zones())
        val redBase = checkNotNull(zones.point("base-red"))
        val blueBase = checkNotNull(zones.point("base-blue"))
        val spawn = checkNotNull(zones.point("tnt-spawn"))
        val red = squad("r", redBase)
        val blue = squad("b", blueBase)
        fun facts() = com.astarworks.astera.bots.ObservedModeFacts("carry-tnt", { _, _, _ -> null }, { _, _ -> null }, spawn.first to spawn.third)

        for ((shieldBar, carriedBar) in listOf(
            "TNT shield 120 HP - break it to grab the TNT" to "red is carrying the TNT",
            "TNT シールド 120 HP - 壊すと TNT を掴める" to "red がTNTを運搬中",
        )) {
            val shielded = facts().also { it.updateRainmaker(listOf(shieldBar), emptyList()) }
            assertThat(shielded.shieldHp()).isEqualTo(120.0)
            assertThat(shielded.tnt()).isNull()
            val breakers = rolesOf(red.map { it.name }, { planner("carry-tnt", src, it, "red", listOf("blue"), shielded, red) }) { redBase }
            assertThat(breakers.values.count { it == Role.SHIELD_BREAKER }).isGreaterThan(0)

            // Red carries; the carrier glows 30 blocks from its spawn. Its name is not on the Tab list for the observers.
            val carrying = facts().also { it.updateRainmaker(listOf(carriedBar), listOf(com.astarworks.astera.bots.GlowingPlayer(null, spawn.first + 30.0, spawn.third))) }
            assertThat(carrying.shieldHp()).isNull()
            assertThat(carrying.tnt()?.carrierTeam).isEqualTo("red")
            assertThat(carrying.tnt()?.x).isEqualTo(spawn.first + 30.0)
            val onTheWay = listOf(Mate("r1", spawn.first + 30.0, spawn.third)) + red.drop(1)
            val redRoles = rolesOf(red.map { it.name }, { planner("carry-tnt", src, it, "red", listOf("blue"), carrying, onTheWay) }) { redBase }
            assertThat(redRoles.values.count { it == Role.CARRIER }).`as`("the unnamed carrier is the mate standing at the glow; nobody else walks to the empty spawn").isEqualTo(1)
            assertThat(redRoles).containsEntry("r1", Role.CARRIER)
            assertThat(redRoles.values).contains(Role.ESCORT)
            val blueRoles = rolesOf(blue.map { it.name }, { planner("carry-tnt", src, it, "blue", listOf("red"), carrying, blue) }) { blueBase }
            assertThat(blueRoles.values).`as`("the enemy squad hunts the carrier").contains(Role.INTERCEPTOR)
        }
    }

    // ---- capture-wool -------------------------------------------------------------------------------------------------

    @Test
    fun `capture-wool - holders stay in the owned zone, the others contest the rest and retake a lost zone`() {
        scenario("bot-roles-capture-wool") {
            val src = objectives("capture-wool")
            val zones = checkNotNull(src.zones())
            val names = zones.names("wool-").sorted()
            assertThat(names.size).isGreaterThanOrEqualTo(2)
            val spawn = checkNotNull(zones.point(names.first()))
            val red = squad("r", spawn)
            val facts = Facts()
            val rn = red.map { it.name }
            fun make(n: String) = planner("capture-wool", src, n, "red", listOf("blue"), facts, red)
            fun steps() = rn.associateWith { n -> make(n).step(0, spawn.first, spawn.third) }

            // Nothing owned: the squad splits over the zones, all contesting.
            val start = steps()
            assertThat(start.values.map { it?.role }.toSet()).containsExactly(Role.CONTESTER)
            assertThat(start.values.mapNotNull { it?.zone }.toSet()).hasSize(minOf(names.size, red.size))

            // Red owns the first zone: one holder stays in it, the three others work the zones red does not own.
            facts.owners[names.first()] = "red"
            val owned = steps()
            val holders = owned.filter { it.value?.role == Role.HOLDER }
            assertThat(holders).hasSize(1)
            assertThat(holders.values.single()?.zone).isEqualTo(names.first())
            assertThat(owned.filter { it.value?.role != Role.HOLDER }.values.map { it?.zone }).doesNotContain(names.first())

            // The holder (first mate) remembers it held the zone, then loses it: it is retaken, not just contested.
            val memory = make("r1")
            memory.step(0, spawn.first, spawn.third)
            facts.owners[names.first()] = "blue"
            val lost = rn.associateWith { n -> if (n == "r1") memory.step(500, spawn.first, spawn.third) else make(n).step(500, spawn.first, spawn.third) }
            assertThat(lost.getValue("r1")?.let { it.role to it.zone }).isNotNull
            assertThat(lost.values.map { it?.role }).contains(Role.RETAKER)
        }
    }

    // ---- carry-mini-tnt -----------------------------------------------------------------------------------------------

    @Test
    fun `carry-mini-tnt - collectors split the clams, a guard keeps the basket, the barrier gates the rush, the holder throws`() {
        scenario("bot-roles-mini-tnt") {
            val runner = player("r1", at = Vec3(0.0, 65.0, -50.0))
            val guard = player("b1", at = Vec3(0.0, 65.0, -50.0))
            val matchId = startMatch("carry-mini-tnt", mapOf(runner to "red", guard to "blue"))
            val src = objectives("carry-mini-tnt")
            val zones = checkNotNull(src.zones())
            val redBase = checkNotNull(zones.point("base-red"))
            val red = squad("r", redBase)
            val facts = Facts()
            fun make(n: String, roster: List<Mate> = red) = planner("carry-mini-tnt", src, n, "red", listOf("blue"), facts, roster)

            // Three collect, one guards the own basket.
            val split = rolesOf(red.map { it.name }, { make(it) }) { redBase }
            assertThat(split).containsEntry("r1", Role.COLLECTOR).containsEntry("r3", Role.COLLECTOR).containsEntry("r4", Role.BASKET_GUARD)
            assertThat(make("r4").step(0, redBase.first, redBase.third)?.kind).isEqualTo("guard")

            // Two collectors standing together take different clams.
            val pair = listOf(Mate("r1", redBase.first, redBase.third), Mate("r2", redBase.first, redBase.third))
            val a = make("r1", pair).step(0, redBase.first, redBase.third)
            val b = make("r2", pair).step(0, redBase.first, redBase.third)
            assertThat(a?.zone).isNotEqualTo(b?.zone)

            // The enemy holds a power TNT: a second guard, and it goes for the holder.
            facts.power = PowerTntFact("blue", "b1", 5.0, 5.0)
            val threatened = rolesOf(red.map { it.name }, { make(it) }) { redBase }
            assertThat(threatened).containsEntry("r3", Role.BASKET_GUARD).containsEntry("r4", Role.BASKET_GUARD)
            assertThat(make("r4").step(0, redBase.first, redBase.third)?.let { it.kind to it.x }).isEqualTo("intercept" to 5.0)

            // Our holder throws at the enemy basket even while its barrier is shut: the power TNT is what opens it (ModePlanner THROWER).
            facts.power = PowerTntFact("red", "r1", redBase.first, redBase.third)
            facts.barrier["blue"] = false
            assertThat(make("r1").step(0, redBase.first, redBase.third)?.kind).isEqualTo("throw")
            facts.barrier["blue"] = true
            assertThat(make("r1").step(0, redBase.first, redBase.third)?.let { it.role to it.kind }).isEqualTo(Role.THROWER to "throw")

            // Barrier open, hands full: r1 plays the real mode, collects, then rushes the clams in.
            facts.power = null
            val rush = make("r1")
            var now = 0L
            var held = 0
            var confirmed = 0
            val seen = ArrayList<Pair<Role?, String>>()
            repeat(40) {
                val have = sim.objectiveStage.carriedCount(runner.id)
                if (have > held) confirmed += have - held
                held = have
                rush.noteConfirmed(confirmed)
                val step = checkNotNull(rush.step(now, runner.position.x, runner.position.z))
                if (seen.lastOrNull() != step.role to step.kind) seen += step.role to step.kind
                runner.moveTo(Vec3(step.x, 65.0, step.z))
                if (rush.clickWanted) clickNearestMarker(runner, matchId)
                advance(10)
                now += 500
            }
            assertThat(seen.take(3)).containsExactly(Role.COLLECTOR to "pickup", Role.RUSHER to "deliver", Role.COLLECTOR to "pickup")
        }
    }

    // ---- steal-jewelry ------------------------------------------------------------------------------------------------

    @Test
    fun `steal-jewelry - thieves steal and bank, a guard keeps the own pedestal and a second one joins when an enemy shows up`() {
        scenario("bot-roles-jewelry") {
            val runner = player("r1", at = Vec3(0.0, 65.0, -50.0))
            val keeper = player("b1", at = Vec3(0.0, 65.0, -50.0))
            startMatch("steal-jewelry", mapOf(runner to "red", keeper to "blue"), stage = "siege-isles-jewelry")
            val src = objectives("steal-jewelry")
            val zones = checkNotNull(src.zones())
            val redPedestal = checkNotNull(zones.point("jewelry-red"))
            val red = squad("r", redPedestal)
            val facts = Facts()
            fun make(n: String) = planner("steal-jewelry", src, n, "red", listOf("blue"), facts, red)
            val names = red.map { it.name }

            assertThat(rolesOf(names, ::make) { redPedestal })
                .containsEntry("r1", Role.THIEF).containsEntry("r3", Role.THIEF).containsEntry("r4", Role.PEDESTAL_GUARD)

            facts.seen = listOf(Sighting("b2", redPedestal.first + 6, redPedestal.third))
            val alarm = rolesOf(names, ::make) { redPedestal }
            assertThat(alarm).containsEntry("r3", Role.PEDESTAL_GUARD).containsEntry("r4", Role.PEDESTAL_GUARD)
            assertThat(make("r3").step(0, redPedestal.first, redPedestal.third)?.kind).isEqualTo("intercept")
            facts.seen = emptyList()

            // r1 plays the thief in the real mode: steals at the blue pedestal, banks at the own base.
            val thief = make("r1")
            val kinds = ArrayList<String>()
            var now = 0L
            var held = 0
            var confirmed = 0
            repeat(60) {
                val have = sim.objectiveStage.carriedCount(runner.id)
                if (have > held) confirmed += have - held
                held = have
                thief.noteConfirmed(confirmed)
                val step = checkNotNull(thief.step(now, runner.position.x, runner.position.z))
                if (kinds.lastOrNull() != step.kind) kinds += step.kind
                runner.moveTo(Vec3(step.x, 65.0, step.z))
                advance(10)
                now += 500
            }
            assertThat(kinds.take(2)).containsExactly("jewel", "bank")
            assertThat(thief.role).isEqualTo(Role.THIEF)
        }
    }

    // ---- annihilation -------------------------------------------------------------------------------------------------

    @Test
    fun `annihilation - attackers dig the lowest pillar of the weaker enemy team, a defender keeps the own pillars`() {
        scenario("bot-roles-annihilation") {
            val src = objectives("annihilation")
            val zones = checkNotNull(src.zones())
            val spawn = checkNotNull(zones.point("center"))
            val red = squad("r", spawn)
            val facts = Facts()
            fun make(n: String) = planner("annihilation", src, n, "red", listOf("blue", "yellow"), facts, red)
            val names = red.map { it.name }

            assertThat(rolesOf(names, ::make) { spawn })
                .containsEntry("r1", Role.PILLAR_ATTACKER).containsEntry("r3", Role.PILLAR_ATTACKER).containsEntry("r4", Role.PILLAR_DEFENDER)
            assertThat(make("r4").step(0, spawn.first, spawn.third)?.kind).isEqualTo("guard")

            // Everything stands (a tie): one of the enemies is attacked at its tier-1 pillar, which is a block of the stage (which one is
            // a seeded draw, see BotTeamFocusScenarioTest).
            val first = checkNotNull(make("r1").step(0, spawn.first, spawn.third))
            val bluePillar = zones.pillars().single { it.team == "blue" && it.tier == 1 }
            val tied = listOf("blue", "yellow").map { t -> zones.pillars().single { it.team == t && it.tier == 1 } }
            assertThat(first.kind).isEqualTo("pillar")
            assertThat(first.strike).isIn(tied.map { Triple(it.x, it.y, it.z) })

            // BOT-A5: an attacker only leaves its pillar trip for an enemy inside the bounded engage radius, so one standing
            // 40 blocks away (the brain compares the distance with this) never aborts it; it digs with the pillar's tool class.
            assertThat(first.engage).isEqualTo(ModePlanner.ATTACK_ENGAGE)
            assertThat(first.engage).isLessThan(40.0)
            assertThat(first.affinity).isEqualTo(bluePillar.toolClass())
            val attacker = make("r1").also { it.step(0, spawn.first, spawn.third) }
            assertThat(attacker.engage).isEqualTo(ModePlanner.ATTACK_ENGAGE)

            // Yellow lost its tier 1: it is the weaker team now, and its tier 2 is next.
            facts.gone += "yellow" to 1
            val second = checkNotNull(make("r1").step(0, spawn.first, spawn.third))
            val yellowPillar = zones.pillars().single { it.team == "yellow" && it.tier == 2 }
            assertThat(second.strike).isEqualTo(Triple(yellowPillar.x, yellowPillar.y, yellowPillar.z))
        }
    }

    @Test
    fun `annihilation - an attacker engages a threatening or weak enemy at 20 blocks, ignores a healthy one, and the far fight is leashed`() {
        scenario("bot-roles-annihilation-engage") {
            val src = objectives("annihilation")
            val spawn = checkNotNull(checkNotNull(src.zones()).point("center"))
            val red = squad("r", spawn)
            val facts = Facts()
            // The bot stands 40 blocks from its pillar. The arena centre is ~90 blocks from the blue tier-1 pillar, farther than
            // OBJECTIVE_LEASH, where a far fight is (rightly) refused, so the centre cannot be the test position.
            val goal = checkNotNull(planner("annihilation", src, "r1", "red", listOf("blue", "yellow"), facts, red).step(0, spawn.first, spawn.third))
            val gl = kotlin.math.hypot(goal.x - spawn.first, goal.z - spawn.third)
            val x = goal.x - (goal.x - spawn.first) / gl * 40.0
            val z = goal.z - (goal.z - spawn.third) / gl * 40.0
            fun attacker() = planner("annihilation", src, "r1", "red", listOf("blue", "yellow"), facts, red)
                .also { assertThat(it.step(0, x, z)?.role).isEqualTo(Role.PILLAR_ATTACKER) }
            // Perpendicular to the way to the objective would be ideal; a point behind the bot is never near the path segment.
            fun behind(d: Double, hp: Double? = null, p: ModePlanner): Sighting {
                val toward = p.step(0, x, z)!!
                val ux = (toward.x - x) / kotlin.math.hypot(toward.x - x, toward.z - z)
                val uz = (toward.z - z) / kotlin.math.hypot(toward.x - x, toward.z - z)
                return Sighting("e", x - ux * d, z - uz * d, hp)
            }

            val a = attacker()
            val healthy = behind(20.0, 1.0, a)
            assertThat(a.engages(healthy, 100, x, z)).isFalse()
            assertThat(a.engages(behind(20.0, 0.2, a), 100, x, z)).isTrue()
            assertThat(a.engages(behind(35.0, 0.2, a), 100, x, z)).isFalse()
            assertThat(a.engages(behind(11.0, 1.0, a), 100, x, z)).isTrue()
            // Damaged a moment ago: the healthy enemy at 20 is the one that hurts, and the memory fades.
            a.noteDamaged(200)
            assertThat(a.engages(healthy, 300, x, z)).isTrue()
            assertThat(a.engages(healthy, 200 + ModePlanner.DAMAGED_MEMORY_MS + 1, x, z)).isFalse()
            // An enemy ON the way to the pillar is a threat to the trip even when healthy and quiet.
            val toward = a.step(0, x, z)!!
            val ux = (toward.x - x) / kotlin.math.hypot(toward.x - x, toward.z - z)
            val uz = (toward.z - z) / kotlin.math.hypot(toward.x - x, toward.z - z)
            assertThat(a.engages(Sighting("e", x + ux * 20, z + uz * 20, 1.0), 400_000, x, z)).isTrue()

            // Leash: a far fight lasts FAR_FIGHT_MS, then the bot goes back to the objective for FAR_COOLDOWN_MS.
            val b = attacker()
            val weak = behind(20.0, 0.2, b)
            assertThat(b.engages(weak, 0, x, z)).isTrue()
            assertThat(b.engages(weak, ModePlanner.FAR_FIGHT_MS - 1_000, x, z)).isTrue()
            assertThat(b.engages(weak, ModePlanner.FAR_FIGHT_MS + 1_000, x, z)).isFalse()
            assertThat(b.engages(weak, ModePlanner.FAR_FIGHT_MS + 3_000, x, z)).isFalse()
            assertThat(b.engages(weak, ModePlanner.FAR_FIGHT_MS + 1_000 + ModePlanner.FAR_COOLDOWN_MS + 1, x, z)).isTrue()
        }
    }

    @Test
    fun `annihilation - an attacker chases the last known spot of its target, then gives up and returns to the pillar`() {
        scenario("bot-roles-annihilation-chase") {
            val src = objectives("annihilation")
            val spawn = checkNotNull(checkNotNull(src.zones()).point("center"))
            val facts = Facts()
            val p = planner("annihilation", src, "r1", "red", listOf("blue", "yellow"), facts, squad("r", spawn))
            val x = spawn.first; val z = spawn.third
            assertThat(p.step(0, x, z)?.kind).isEqualTo("pillar")

            p.noteLastKnown(x + 15, z, seenAt = 1_000)
            val chase = checkNotNull(p.step(1_500, x, z))
            assertThat(chase.kind).isEqualTo("chase")
            assertThat(chase.x).isEqualTo(x + 15)
            assertThat(chase.z).isEqualTo(z)
            assertThat(chase.engage).isEqualTo(ModePlanner.ATTACK_ENGAGE_FAR)

            // Never arriving: the chase times out and the plan is the pillar again, not endlessly the old spot. The target keeps
            // being seen at that spot (fresh memory, shorter than CHASE_MS), so it is the chase clock that ends the chase.
            p.noteLastKnown(x + 15, z, seenAt = 1_500 + ModePlanner.CHASE_MS)
            assertThat(p.step(1_500 + ModePlanner.CHASE_MS + 100, x, z)?.kind).isEqualTo("pillar")
            p.noteLastKnown(x + 15, z, seenAt = 1_500 + ModePlanner.CHASE_MS + 200)
            assertThat(p.step(1_500 + ModePlanner.CHASE_MS + 300, x, z)?.kind).isEqualTo("pillar") // cooldown

            // A stale memory is not chased; reaching the spot ends the chase.
            val q = planner("annihilation", src, "r1", "red", listOf("blue", "yellow"), facts, squad("r", spawn))
            q.step(0, x, z)
            q.noteLastKnown(x + 15, z, seenAt = 0)
            assertThat(q.step(ModePlanner.CHASE_MEMORY_MS + 1, x, z)?.kind).isEqualTo("pillar")
            q.noteLastKnown(x + 1, z, seenAt = 10_000)
            assertThat(q.step(10_100, x, z)?.kind).isEqualTo("pillar") // already within CHASE_ARRIVE
        }
    }

    @Test
    fun `annihilation - preparation keeps attackers out of enemy bases and gathering, the open pillars send them to the pillar`() {
        scenario("bot-roles-annihilation-preparation") {
            val src = objectives("annihilation")
            val zones = checkNotNull(src.zones())
            val spawn = checkNotNull(zones.point("center"))
            val x = spawn.first; val z = spawn.third
            val blue = zones.pillars().single { it.team == "blue" && it.tier == 1 }
            val facts = Facts().also { it.phase = 1 }
            fun attacker(f: ModeFacts = facts) = planner("annihilation", src, "r1", "red", listOf("blue", "yellow"), f, squad("r", spawn))

            // Phase 1: no pillar step (the brain gathers instead), enemy bases are off limits, the field is not.
            val p1 = attacker()
            assertThat(p1.step(0, x, z)).isNull()
            assertThat(p1.preparing(0)).isTrue()
            assertThat(p1.offLimits(0, blue.x + 0.5, blue.z + 0.5)).isTrue()
            assertThat(p1.offLimits(0, x, z)).isFalse()
            val own = zones.pillars().first { it.team == "red" }
            assertThat(p1.offLimits(0, own.x + 0.5, own.z + 0.5)).isFalse()

            // An enemy in the field is fought, one inside an enemy base is not followed, and a target last seen there is not chased.
            val gather = attacker().also { it.step(0, x, z) }
            assertThat(gather.engages(Sighting("e", x + 20, z, 1.0), 100, x, z)).isTrue()
            assertThat(gather.engages(Sighting("e", blue.x + 5.0, blue.z.toDouble(), 0.2), 100, blue.x - 60.0, blue.z.toDouble())).isFalse()
            gather.noteLastKnown(blue.x + 5.0, blue.z.toDouble(), seenAt = 200)
            assertThat(gather.step(300, x, z)).isNull()

            // Phase 2 opens the pillars: the same bot walks to the pillar and a far enemy in the base can be engaged again.
            facts.phase = 2
            val p2 = attacker()
            val step = checkNotNull(p2.step(0, x, z))
            assertThat(step.kind).isEqualTo("pillar")
            // The enemy is a seeded draw between the tied enemies (never the alphabetical first): any enemy tier-1 pillar.
            assertThat(step.strike).isIn(zones.pillars().filter { it.team != "red" && it.tier == 1 }.map { Triple(it.x, it.y, it.z) })
            assertThat(p2.offLimits(0, blue.x + 0.5, blue.z + 0.5)).isFalse()

            // A host without a phase bar: the build-phase timer decides, and the highest phase seen sticks.
            val timed = attacker(Facts().also { it.phase = null })
            assertThat(timed.step(0, x, z)).isNull()
            assertThat(timed.step(ModePlanner.BUILD_PHASE_MS - 1, x, z)).isNull()
            assertThat(timed.step(ModePlanner.BUILD_PHASE_MS + 1, x, z)?.kind).isEqualTo("pillar")
        }
    }

    @Test
    fun `annihilation - own pillar damage flips the nearest attackers to defend and they switch back after a quiet period`() {
        scenario("bot-roles-annihilation-switch") {
            val src = objectives("annihilation")
            val zones = checkNotNull(src.zones())
            val spawn = checkNotNull(zones.point("center"))
            val own = zones.pillars().first { it.team == "red" && it.tier == 1 }
            val at = mapOf(
                "r1" to (own.x + 2.0 to own.z + 2.0), "r2" to (own.x + 12.0 to own.z + 0.0), "r3" to (spawn.first to spawn.third), "r4" to (own.x + 5.0 to own.z + 0.0),
            )
            val roster = at.map { (n, p) -> Mate(n, p.first, p.second) }
            val facts = Facts()
            val planners = at.keys.associateWith { planner("annihilation", src, it, "red", listOf("blue", "yellow"), facts, roster) }
            fun roles(now: Long) = planners.mapValues { (n, p) -> p.step(now, at.getValue(n).first, at.getValue(n).second); p.role }

            facts.pillarHps[1] = PillarHpFact(100, 100)
            assertThat(roles(0)).containsEntry("r1", Role.PILLAR_ATTACKER).containsEntry("r2", Role.PILLAR_ATTACKER)
                .containsEntry("r3", Role.PILLAR_ATTACKER).containsEntry("r4", Role.PILLAR_DEFENDER)

            // The pillar loses HP: the two attackers nearest to it defend, the far one keeps attacking.
            facts.pillarHps[1] = PillarHpFact(70, 100)
            assertThat(roles(1_000)).containsEntry("r1", Role.PILLAR_DEFENDER).containsEntry("r2", Role.PILLAR_DEFENDER)
                .containsEntry("r3", Role.PILLAR_ATTACKER).containsEntry("r4", Role.PILLAR_DEFENDER)
            val guard = checkNotNull(planners.getValue("r1").step(1_500, at.getValue("r1").first, at.getValue("r1").second))
            assertThat(guard.kind).isEqualTo("guard")
            assertThat(guard.x).isEqualTo(own.x + 0.5)

            // Hysteresis: still defending while no further hit came but the quiet period is not over, back to attack after it.
            assertThat(roles(1_000 + ModePlanner.DEFEND_QUIET_MS - 1_000)).containsEntry("r1", Role.PILLAR_DEFENDER)
            assertThat(roles(1_000 + ModePlanner.DEFEND_QUIET_MS + 1_000)).containsEntry("r1", Role.PILLAR_ATTACKER).containsEntry("r2", Role.PILLAR_ATTACKER)
        }
    }

    // ---- bots make objective progress on the real stage (BOT-04 / MODE-17: snapshot facts) ----------------------------

    /** The sidebar count of [team] as [viewer] sees it. */
    private fun ScenarioScope.countOf(viewer: SimEntity, team: String): Int? =
        checkNotNull(sim.hud.sidebar(viewer.id)).lines.map { it as HudComponent.Text }
            .filter { it.messageKey.value == "astera.hud.sidebar.count" && it.placeholders["color"] == team }
            .firstNotNullOfOrNull { it.placeholders["count"]?.toIntOrNull() }

    /** [ModeFacts] read from the mode's own state query, like a bot sharing a process with the server. */
    private fun ScenarioScope.snapshotFacts(mode: String, match: MatchId): ModeFacts {
        val query = sim.services.getOrThrow<IGameModeFrameworkRegistry>().get(GameModeId(mode)) as IObjectiveStateQuery
        return SnapshotModeFacts(
            snapshot = { query.objectiveSnapshot(match) },
            players = { id -> sim.world.find(id)?.let { PlayerView(it.name, it.team?.value, it.position.x, it.position.z) } },
        )
    }

    /**
     * Drives a lone red bot through the real [mode] with snapshot facts until the red count drops below 100 (it scored) or the
     * budget runs out; a blue player stands idle far away. Returns the red count at the end.
     */
    private fun ScenarioScope.playUntilScored(mode: String, stage: String, rounds: Int, squad: Int = 1): Pair<Int?, List<String>> {
        // [squad] red bots play together: Clam Blitz needs more clams in the team's hands (power_need) than one player may carry (carry_limit).
        val runners = (1..squad).map { player("runner$it", at = Vec3(0.0, 65.0, -50.0)) }
        val idle = player("idle", at = Vec3(0.0, 65.0, -50.0))
        val match = startMatch(mode, runners.associateWith { "red" } + (idle to "blue"), stage = stage)
        // The snapshot facts read a holder's team off the world entity, which the scenario's startMatch does not set.
        runners.forEach { it.team = TeamId("red") }
        idle.team = TeamId("blue")
        val src = objectives(mode)
        val facts = snapshotFacts(mode, match)
        val bots = runners.map { r -> planner(mode, src, r.name, "red", listOf("blue"), facts, emptyList()) }
        val kinds = ArrayList<String>()
        val held = IntArray(squad)
        val confirmed = IntArray(squad)
        var now = 0L
        repeat(rounds) {
            if ((countOf(runners[0], "red") ?: 100) < 100 || eventsOf<MatchEnded>().isNotEmpty()) return countOf(runners[0], "red") to kinds
            val roster = runners.map { Mate(it.name, it.position.x, it.position.z) }
            for ((i, runner) in runners.withIndex()) {
                val bot = bots[i]
                bot.noteRoster(roster)
                val have = sim.objectiveStage.carriedCount(runner.id)
                if (have > held[i]) confirmed[i] += have - held[i]
                held[i] = have
                bot.noteConfirmed(confirmed[i])
                val step = checkNotNull(bot.step(now, runner.position.x, runner.position.z)) { "mode has a plan" }
                if (i == 0 && kinds.lastOrNull() != step.kind) kinds += step.kind
                runner.moveTo(Vec3(step.x, 65.0, step.z))
                if (bot.clickWanted) clickNearestMarker(runner, match)
            }
            advance(10)
            now += 500
        }
        return countOf(runners[0], "red") to kinds
    }

    @Test
    fun `splat zones - a bot takes the zones and the red count falls`() {
        scenario("bot-progress-zones") {
            // temple-trouble is the stage content/matchmaking/queue.yaml routes capture-wool to (ADR-0281); its siege-isles-wool copy is unrouted.
            val (count, kinds) = playUntilScored("capture-wool", "temple-trouble", 600)
            assertThat(count).`as`("red count after the bot held zones; plan $kinds").isLessThan(100)
        }
    }

    @Test
    fun `rainmaker - a bot breaks the shield, carries the TNT through the checkpoints and scores`() {
        scenario("bot-progress-rainmaker") {
            val (count, kinds) = playUntilScored("carry-tnt", "siege-isles-tnt", 900)
            assertThat(count).`as`("red count after delivery; plan $kinds").isLessThan(100)
        }
    }

    @Test
    fun `clam blitz - a bot collects clams, throws the power TNT and plants minis into the open basket`() {
        scenario("bot-progress-clams") {
            val (count, kinds) = playUntilScored("carry-mini-tnt", "siege-isles-mini-tnt", 1500, squad = 2)
            assertThat(count).`as`("red count after planting; plan $kinds").isLessThan(100)
        }
    }

    @Test
    fun `jewelry - a bot steals a jewel, banks it and the red count falls`() {
        scenario("bot-progress-jewelry") {
            val (count, kinds) = playUntilScored("steal-jewelry", "siege-isles-jewelry", 600)
            assertThat(count).`as`("red count after banking; plan $kinds").isLessThan(100)
        }
    }
}

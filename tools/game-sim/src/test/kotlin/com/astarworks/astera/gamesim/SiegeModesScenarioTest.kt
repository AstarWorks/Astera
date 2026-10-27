package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.ModePlanner
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.gamemode.siege.IObjectiveStateQuery
import com.astarworks.astera.match.application.gamemode.siege.ObjectiveSnapshot
import com.astarworks.astera.match.application.port.outbound.IGameModeFrameworkRegistry
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.mode.GameModeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.floor

/**
 * WP11 / MODE-17: every siege (objective) mode is played to its win condition on the real `content/` (stages,
 * game-mode YAML config, queue lanes) with two teams and the minimum number of players. Region positions are read from
 * the stage registry at runtime so the scenarios follow the stage layouts.
 */
class SiegeModesScenarioTest {

    // siege-isles-jewelry: base-red is x -122..-62, jewelry-blue sits at x 98..100 z -16..-14 (stage-local).
    private val redBase = Vec3(-92.0, 65.0, 0.0)
    private val nowhere = Vec3(0.0, 65.0, -50.0)

    // ---- helpers ----------------------------------------------------------

    private fun ScenarioScope.ended(): MatchEnded = eventsOf<MatchEnded>().single()

    private fun ScenarioScope.phaseOf(id: MatchId) = sim.services.getOrThrow<IMatchRepository>().findById(id)?.phase

    private fun ScenarioScope.stageOf(id: String): Stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(StageId(id))) { "no stage $id" }

    /** Where a player stands to be inside this region (one block above its floor). */
    private fun Region.spot(): Vec3 = Vec3((min.x + max.x) / 2.0, (min.y + 1.0).coerceAtMost(max.y), (min.z + max.z) / 2.0)

    /** The block cell a mode puts its marker on (SiegeGameMode.cellOf). */
    private fun Region.cell(): Vec3 = Vec3(floor((min.x + max.x) / 2), floor(min.y), floor((min.z + max.z) / 2))

    private fun ScenarioScope.snapshotOf(mode: String, id: MatchId): ObjectiveSnapshot =
        checkNotNull((sim.services.getOrThrow<IGameModeFrameworkRegistry>().get(GameModeId(mode)) as IObjectiveStateQuery).objectiveSnapshot(id)) { "no $mode snapshot" }

    private fun ScenarioScope.clamsOf(id: MatchId) = snapshotOf("carry-mini-tnt", id) as ObjectiveSnapshot.Clams

    /** [shooter] (holding a pistol) shoots [victim] from close range until a new [PlayerKilled] for it lands. */
    private fun ScenarioScope.kill(shooter: SimEntity, victim: SimEntity) {
        val before = eventsOf<PlayerKilled>().count { it.victim == victim.id }
        repeat(400) {
            if (eventsOf<PlayerKilled>().count { p -> p.victim == victim.id } > before) return
            if (!sim.isAlive(victim.id)) {
                advance(10)
                return@repeat
            }
            if (ammo(shooter) == 0) shooter.drop(sneaking = true)
            shooter.faces(victim)
            shooter.rightClick()
            advance(10)
        }
        error("${victim.name} was not killed by ${shooter.name}")
    }

    /** Waits for [victim] to respawn (alive again), then puts them at [at] without announcing a move. */
    private fun ScenarioScope.respawnAt(victim: SimEntity, at: Vec3) {
        check(advanceUntil(600) { sim.isAlive(victim.id) }) { "${victim.name} did not respawn" }
        // Status effects outlive the respawn: let a leftover burn tick out (it can kill again).
        advance(120)
        check(advanceUntil(600) { sim.isAlive(victim.id) }) { "${victim.name} did not respawn" }
        victim.position = at
    }

    /** Walks [who] to [at] and stays there long enough for a pickup / deposit channel (default 15 ticks) to complete. */
    private fun ScenarioScope.hold(who: SimEntity, at: Vec3, ticks: Int = 40) {
        who.moveTo(at)
        advance(ticks)
    }

    // ---- carry-tnt (Rainmaker) --------------------------------------------

    /** [who] stands in tnt-spawn until the shield is gone and the pickup hold completes (200 HP at 10 HP/s alone). */
    private fun ScenarioScope.takeTnt(who: SimEntity, spawn: Vec3) {
        who.moveTo(spawn)
        check(advanceUntil(20 * 90) { sim.objectiveStage.carrying(who.id) != null }) { "${who.name} never got the TNT" }
    }

    @Test
    fun `carry tnt - shield, a dead carrier drops the tnt and it returns to the centre`() {
        scenario("siege-carry-tnt") {
            val stage = stageOf("siege-isles-tnt")
            val spawn = stage.region("tnt-spawn")!!
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-tnt")
            assertThat(blockAt(spawn.cell())).isEqualTo("minecraft:tnt")

            // The TNT is shielded: standing in tnt-spawn wears the shield down (a boss bar shows it) but does not pick it up yet.
            runner.moveTo(spawn.spot())
            advance(60)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("the shield blocks the pickup").isNull()
            assertThat(sim.objectiveStage.bar(runner.id, "shield")?.progress).`as`("the shield bar shows the damage").isLessThan(1.0)
            // Broken after 200 HP; then the pickup is a hold: the carrier glows and is slowed. The guard shoots them down.
            guard.holds("pistol")
            check(advanceUntil(20 * 40) { sim.objectiveStage.carrying(runner.id) != null }) { "runner never picked the TNT up" }
            guard.moveTo(spawn.spot().copy(z = spawn.spot().z - 8.0))
            kill(guard, runner)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("dead carrier is stripped").isNull()
            assertThat(sim.objectiveStage.markers(matchId).keys).`as`("the dropped TNT is marked").isNotEmpty()
            guard.moveTo(nowhere)

            // drop_return_seconds: nobody takes it, it goes back to the CENTRE (not a checkpoint) with a fresh shield.
            advance(20 * 14)
            respawnAt(runner, nowhere)
            val shield = (snapshotOf("carry-tnt", matchId) as ObjectiveSnapshot.Rainmaker)
            assertThat(shield.shieldHp).`as`("the returned TNT is shielded again").isEqualTo(shield.shieldMax)
            takeTnt(runner, spawn.spot())
        }
    }

    @Test
    fun `carry tnt - the count follows the route progress and reaching the enemy goal is a knockout`() {
        scenario("siege-carry-tnt-goal") {
            val stage = stageOf("siege-isles-tnt")
            val spawn = stage.region("tnt-spawn")!!
            val deliver = stage.region("deliver-blue")!!
            val checkpoints = stage.regionsWithPrefix("checkpoint-blue-").sortedBy { it.name }
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-tnt")
            takeTnt(runner, spawn.spot())

            // Delivering without the checkpoints scores nothing: progress is capped at the next checkpoint.
            hold(runner, deliver.spot(), 80)
            assertThat(eventsOf<MatchEnded>()).`as`("the goal is closed until the checkpoints are held").isEmpty()
            assertThat((snapshotOf("carry-tnt", matchId) as ObjectiveSnapshot.Rainmaker).progress.getValue(TeamId("red"))).isLessThan(0.99)

            val before = (snapshotOf("carry-tnt", matchId) as ObjectiveSnapshot.Rainmaker).progress.getValue(TeamId("red"))
            checkpoints.forEach { hold(runner, it.spot(), 90) }
            val mid = (snapshotOf("carry-tnt", matchId) as ObjectiveSnapshot.Rainmaker).progress.getValue(TeamId("red"))
            assertThat(mid).`as`("every checkpoint held moves the count further").isGreaterThan(before)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            hold(runner, deliver.spot(), 80)
            val end = ended()
            assertThat(end.winningTeam).`as`("reaching the goal is a knockout").isEqualTo(TeamId("red"))
            assertThat(blockAt(spawn.cell())).`as`("marker removed").isEqualTo("minecraft:grass_block")
        }
    }

    // ---- capture-wool (Splat Zones) ---------------------------------------

    /** [who] stands on [zone] until its marker block turns to [colour] wool. */
    private fun ScenarioScope.capture(who: SimEntity, zone: Region, colour: String) {
        who.moveTo(zone.spot())
        check(advanceUntil(20 * 16) { blockAt(zone.cell()) == "minecraft:${colour}_wool" }) { "${who.name} did not capture the zone" }
    }

    @Test
    fun `capture wool - a contested zone does not progress, holding every zone counts the team down to a knockout`() {
        scenario("siege-capture-wool") {
            val stage = stageOf("siege-isles")
            val zones = stage.regionsWithPrefix("wool-")
            val holder = player("holder", at = nowhere)
            val rival = player("rival", at = nowhere)
            val matchId = startMatch("capture-wool", mapOf(holder to "red", rival to "blue"))
            val first = zones.first()
            assertThat(blockAt(first.cell())).isEqualTo("minecraft:white_wool")

            // Both stand in the first zone for longer than the capture time: nobody captures.
            holder.moveTo(first.spot())
            rival.moveTo(first.spot().copy(x = first.spot().x + 1.0))
            advance(20 * 14)
            assertThat(blockAt(first.cell())).isEqualTo("minecraft:white_wool")
            assertThat(sim.objectiveStage.bar(holder.id, "objective")?.progress).`as`("contested: the bar shows no progress").isEqualTo(0.0)
            rival.moveTo(nowhere)

            // The holder takes every zone in turn; the count only moves while ONE team owns all of them.
            zones.forEach { capture(holder, it, "red") }
            assertThat(eventsOf<MatchEnded>()).isEmpty()
            assertThat(advanceUntil(20 * 130) { eventsOf<MatchEnded>().isNotEmpty() }).`as`("100 count at 1 per second").isTrue()
            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(blockAt(first.cell())).`as`("markers restored at match end").isEqualTo("minecraft:grass_block")
        }
    }

    @Test
    fun `capture wool - retaking a zone costs the new owner a penalty before its count moves`() {
        scenario("siege-capture-wool-penalty") {
            val stage = stageOf("siege-isles")
            val zones = stage.regionsWithPrefix("wool-")
            val holder = player("holder", at = nowhere)
            val rival = player("rival", at = nowhere)
            val matchId = startMatch("capture-wool", mapOf(holder to "red", rival to "blue"))

            zones.forEach { capture(holder, it, "red") }
            holder.moveTo(nowhere)
            advance(20 * 20) // the holder's count runs for ~20 s: that is what the previous owner gained
            assertThat((snapshotOf("capture-wool", matchId) as ObjectiveSnapshot.Zones).penaltySeconds.values.sum()).isZero()

            // The rival retakes one zone: it owes a share of what the holder gained before its own count can move.
            capture(rival, zones.first(), "blue")
            val owed = (snapshotOf("capture-wool", matchId) as ObjectiveSnapshot.Zones).penaltySeconds[TeamId("blue")] ?: 0
            assertThat(owed).`as`("a retake builds a penalty").isGreaterThan(0)
            assertThat(eventsOf<MatchEnded>()).isEmpty()
        }
    }

        // ---- steal-jewelry ----------------------------------------------------

    @Test
    fun `steal jewelry - a dead thief drops the jewels, the owners recover them, banking five jewels knocks out`() {
        scenario("siege-steal-jewelry") {
            val thief = player("thief", at = nowhere)
            val keeper = player("keeper", at = nowhere)
            val matchId = startMatch("steal-jewelry", mapOf(thief to "red", keeper to "blue"), stage = "siege-isles-jewelry")
            val bluePedestal = Vec3(99.0, 65.0, -15.0)
            val pedestalMarker = Vec3(99.0, 64.0, -15.0)
            assertThat(blockAt(pedestalMarker)).isEqualTo("minecraft:diamond_block")

            // Thief steals a jewel (a 2.5 s hold); the keeper shoots them down before banking and the jewel lies where it fell.
            keeper.holds("pistol")
            hold(thief, bluePedestal, 110)
            assertThat(sim.objectiveStage.carrying(thief.id)).`as`("thief wears a jewel").isNotNull()
            keeper.moveTo(Vec3(99.0, 65.0, -5.0))
            kill(keeper, thief)
            assertThat(sim.objectiveStage.carrying(thief.id)).isNull()
            assertThat(sim.objectiveStage.markers(matchId).keys.filter { it.startsWith("pile-") }).`as`("jewels on the ground").isNotEmpty()

            // The keeper's team recovers its own jewels by standing on the pile; nothing was banked.
            hold(keeper, bluePedestal)
            assertThat(sim.objectiveStage.markers(matchId).keys.filter { it.startsWith("pile-") }).`as`("pile recovered").isEmpty()
            respawnAt(thief, nowhere)
            hold(thief, redBase)
            assertThat(eventsOf<MatchEnded>()).`as`("nothing was banked").isEmpty()

            // Now the thief steals every jewel (6 per team, 5 are enough: -20 each) and banks them.
            keeper.moveTo(nowhere)
            repeat(5) {
                hold(thief, bluePedestal, 110)
                thief.moveTo(nowhere)
            }
            assertThat(eventsOf<MatchEnded>()).isEmpty()
            hold(thief, redBase)

            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(blockAt(pedestalMarker)).isEqualTo("minecraft:grass_block")
        }
    }

    @Test
    fun `steal jewelry - the steal takes 4 seconds and damage interrupts it`() {
        scenario("siege-steal-jewelry-channel") {
            val thief = player("thief", at = nowhere)
            val keeper = player("keeper", at = nowhere)
            startMatch("steal-jewelry", mapOf(thief to "red", keeper to "blue"), stage = "siege-isles-jewelry")
            val bluePedestal = Vec3(99.0, 65.0, -15.0)

            thief.moveTo(bluePedestal)
            advance(40)
            assertThat(sim.objectiveStage.carrying(thief.id)).`as`("2 s is not enough").isNull()
            assertThat(sim.objectiveStage.bar(thief.id, "channel")?.progress ?: 0.0).`as`("the steal bar fills").isGreaterThan(0.0)
            // One hit cancels the hold; the bar starts over.
            keeper.holds("pistol")
            keeper.moveTo(Vec3(99.0, 65.0, -5.0))
            keeper.faces(thief)
            keeper.rightClick()
            check(advanceUntil(40) { eventsOf<DamageDealt>().any { it.target == thief.id } }) { "the keeper never hit the thief" }
            advance(30)
            assertThat(sim.objectiveStage.carrying(thief.id)).`as`("the interrupted hold restarted from zero").isNull()
            keeper.moveTo(nowhere)
            check(advanceUntil(160) { sim.objectiveStage.carrying(thief.id) != null }) { "an undisturbed hold completes" }
        }
    }

    @Test
    fun `steal jewelry - a bot whose carry was shot down walks back and re-picks the pile`() {
        scenario("siege-steal-jewelry-bot-repick") {
            val runner = player("runner", at = nowhere)
            val keeper = player("keeper", at = nowhere)
            startMatch("steal-jewelry", mapOf(runner to "red", keeper to "blue"), stage = "siege-isles-jewelry")
            val src = checkNotNull(StageObjectives.load(GameSim.defaultContentDir().toFile(), "steal-jewelry")) { "stage did not load" }
            val plan = ModePlanner("steal-jewelry", "runner", "red", listOf("blue"), checkNotNull(src.zones()))
            var now = 0L
            var held = 0
            var confirmed = 0
            fun round(): ModePlanner.Step {
                val have = sim.objectiveStage.carriedCount(runner.id)
                if (have > held) confirmed += have - held
                held = have
                plan.noteConfirmed(confirmed)
                val step = checkNotNull(plan.step(now, runner.position.x, runner.position.z))
                runner.moveTo(Vec3(step.x, 65.0, step.z))
                advance(10)
                now += 500
                return step
            }

            repeat(40) { if (sim.objectiveStage.carrying(runner.id) == null) round() }
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("the bot stole a jewel").isNotNull()

            keeper.holds("pistol")
            keeper.moveTo(Vec3(runner.position.x, 65.0, runner.position.z + 10.0))
            val deathAt = runner.position
            kill(keeper, runner)
            plan.onDeath(deathAt.x, deathAt.z)
            keeper.moveTo(nowhere)
            respawnAt(runner, nowhere)

            val first = round()
            assertThat(first.kind).`as`("the first goal after respawn is the dropped pile").isEqualTo("recover")
            repeat(40) { if (sim.objectiveStage.carrying(runner.id) == null) round() }
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("the bot picked the pile up again").isNotNull()
        }
    }

    // ---- carry-mini-tnt (Clam Blitz) --------------------------------------

    @Test
    fun `carry mini tnt - ten clams make a power TNT, only a thrown power TNT opens the basket barrier and it closes again`() {
        scenario("siege-clam-blitz") {
            val stage = stageOf("siege-isles-mini-tnt")
            val clams = stage.regionsWithPrefix("clam-")
            check(clams.size >= 10 && stage.region("basket-blue") != null) { "stage needs clam-<n> (>= 10) and basket-<team> regions" }
            val a = player("a", at = nowhere)
            val b = player("b", at = nowhere)
            val rival = player("rival", at = nowhere)
            val matchId = startMatch("carry-mini-tnt", mapOf(a to "red", b to "red", rival to "blue"), stage = "siege-isles-mini-tnt")
            val blueBasket = stage.region("basket-blue")!!.spot()

            // A closed barrier takes no minis: one clam carried into the enemy basket stays carried.
            hold(a, clams[0].spot(), 40)
            assertThat(clamsOf(matchId).carried[a.id]).`as`("a picked a clam up").isEqualTo(1)
            hold(a, blueBasket, 60)
            assertThat(clamsOf(matchId).baskets.getValue(TeamId("blue")).open).`as`("the barrier starts closed").isFalse()
            assertThat(clamsOf(matchId).carried[a.id]).`as`("a closed barrier takes nothing").isEqualTo(1)

            // a fills up to 8, b adds the last 2: the team's clams reach 10 and b holds a power TNT.
            clams.drop(1).take(7).forEach { hold(a, it.spot(), 40) }
            clams.drop(8).take(2).forEach { hold(b, it.spot(), 40) }
            assertThat(clamsOf(matchId).powerHolders).`as`("ten clams make a power TNT").containsExactly(b.id)

            // Throwing it into the enemy basket opens the barrier for about 10 s.
            hold(b, blueBasket, 60)
            assertThat(clamsOf(matchId).baskets.getValue(TeamId("blue")).open).`as`("the power TNT broke the barrier").isTrue()
            advance(20 * 12)
            assertThat(clamsOf(matchId).baskets.getValue(TeamId("blue")).open).`as`("the barrier closes again").isFalse()
        }
    }

    // ---- queue lanes ------------------------------------------------------

    @ParameterizedTest(name = "lane {0} forms a match on its own stage for two queued players")
    @ValueSource(strings = ["carry-tnt", "capture-wool", "steal-jewelry", "carry-mini-tnt"])
    fun `each siege lane forms a match once both sides have a player`(lane: String) {
        scenario("siege-lane-$lane") {
            val solo = player("solo")
            val rival = player("rival")
            val mode = MatchmakingMode.Custom(GameModeId(lane))
            check(sim.services.getOrThrow<IMatchmaker>().enqueue(solo.id, mode) is Result.Success)
            check(sim.services.getOrThrow<IMatchmaker>().enqueue(rival.id, mode) is Result.Success)

            assertThat(advanceUntil(600) { eventsOf<MatchStarted>().isNotEmpty() }).isTrue()
            val started = eventsOf<MatchStarted>().single()
            assertThat(started.modeKey).isEqualTo(lane)
            // MODE-06 / ADR-0281: capture-wool runs on an imported world, the other lanes on their own siege-isles copy.
            val stage = when (lane) {
                "capture-wool" -> "temple-trouble"
                else -> "siege-isles-${lane.removePrefix("carry-").removePrefix("steal-")}"
            }
            assertThat(started.stageId).isEqualTo(StageId(stage))
            // The 10 s in-arena countdown ends and the match is live.
            assertThat(advanceUntil(20 * 15) { phaseOf(started.matchId) == MatchPhase.ACTIVE }).isTrue()
        }
    }
}

package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.ModePlanner
import com.astarworks.astera.bots.StageObjectives
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.mode.GameModeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * WP11: every siege (objective) mode is played to its win condition on the real
 * `content/` (stage `siege-isles`, game-mode YAML config, queue lanes) with two
 * teams and the minimum number of players.
 */
class SiegeModesScenarioTest {

    // The sky-island siege stage (content/stages/siege-isles.yaml): top layer y=64, players stand at y=65. Stage-local coordinates.
    private val tntSpawn = Vec3(0.0, 65.0, -6.0)
    private val redBase = Vec3(-43.0, 65.0, 0.0)
    private val blueBase = Vec3(43.0, 65.0, 0.0)

    // siege-isles-tnt: delivery pedestals deep in each base and the checkpoints on the way to them (routes in order).
    private val deliverRed = Vec3(-52.0, 65.0, 0.0)
    private val deliverBlue = Vec3(52.0, 65.0, 0.0)
    private val redRoute = listOf(Vec3(-24.0, 65.0, 6.0), Vec3(-38.0, 65.0, -10.0))
    private val blueRoute = listOf(Vec3(24.0, 65.0, -6.0), Vec3(38.0, 65.0, 10.0))
    private val nowhere = Vec3(0.0, 65.0, -50.0)

    // ---- helpers ----------------------------------------------------------

    private fun ScenarioScope.ended(): MatchEnded = eventsOf<MatchEnded>().single()

    private fun ScenarioScope.phaseOf(id: MatchId) = sim.services.getOrThrow<IMatchRepository>().findById(id)?.phase

    private fun ScenarioScope.teamOf(id: MatchId, who: SimEntity): TeamId? =
        sim.services.getOrThrow<IMatchRepository>().findById(id)?.teams?.firstOrNull { who.id in it.members }?.id

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

    // ---- carry-tnt --------------------------------------------------------

    /** The carrier of [who]'s team walks the enemy route: every checkpoint of [owner] (3 s hold each), then its deliver pedestal (2 s hold). */
    private fun ScenarioScope.deliverTo(who: SimEntity, checkpoints: List<Vec3>, deliver: Vec3) {
        checkpoints.forEach { hold(who, it, 80) }
        hold(who, deliver, 60)
    }

    /** [who] stands in tnt-spawn until the shield is gone and the pickup hold completes (200 HP at 10 HP/s alone, plus any respawn wait). */
    private fun ScenarioScope.takeTnt(who: SimEntity) {
        who.moveTo(tntSpawn)
        check(advanceUntil(20 * 90) { sim.objectiveStage.carrying(who.id) != null }) { "${who.name} never got the TNT" }
    }

    @Test
    fun `carry tnt - shield, respawn delay, checkpoints, three deliveries win, a dead carrier drops the tnt where it fell`() {
        scenario("siege-carry-tnt") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-tnt")
            assertThat(blockAt(tntSpawn.copy(y = 64.0))).isEqualTo("minecraft:tnt")

            // Delivering without the TNT scores nothing.
            hold(runner, deliverBlue, 80)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // The TNT is shielded: standing in tnt-spawn wears the shield down (a boss bar shows it) but does not pick it up yet.
            runner.moveTo(tntSpawn)
            advance(60)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("the shield blocks the pickup").isNull()
            assertThat(sim.objectiveStage.bar(runner.id, "shield")?.progress).`as`("the shield bar shows the damage").isLessThan(1.0)
            // Broken after 200 HP; then the pickup is a hold: the carrier glows and is slowed. The guard shoots them down.
            guard.holds("pistol")
            check(advanceUntil(20 * 40) { sim.objectiveStage.carrying(runner.id) != null }) { "runner never picked the TNT up" }
            guard.moveTo(Vec3(0.0, 65.0, -14.0))
            kill(guard, runner)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("dead carrier is stripped").isNull()
            assertThat(sim.objectiveStage.markers(matchId).keys).`as`("the dropped TNT is marked").isNotEmpty()
            respawnAt(runner, nowhere)

            // The guard (blue) picks the dropped TNT up (no shield left), holds the red checkpoints and delivers it: blue 1/3.
            hold(guard, tntSpawn, 60)
            assertThat(sim.objectiveStage.carrying(guard.id)).isNotNull()
            hold(guard, deliverRed, 60)
            assertThat(eventsOf<MatchEnded>()).`as`("deliver without the checkpoints scores nothing").isEmpty()
            assertThat(sim.objectiveStage.carrying(guard.id)).`as`("still carrying").isNotNull()
            deliverTo(guard, redRoute, deliverRed)
            assertThat(sim.objectiveStage.carrying(guard.id)).`as`("delivered: the carry is gone").isNull()
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // After a delivery the TNT is gone for 30 s: the runner waits in the zone and gets nothing for 20 s.
            runner.moveTo(tntSpawn)
            advance(20 * 20)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("the TNT is still respawning").isNull()

            // Runner now carries it to the blue base three times (each time shield again, then the checkpoints, then the pedestal).
            repeat(3) {
                guard.moveTo(nowhere)
                takeTnt(runner)
                deliverTo(runner, blueRoute, deliverBlue)
                if (it < 2) {
                    assertThat(eventsOf<MatchEnded>()).`as`("red ${it + 1}/3").isEmpty()
                    runner.moveTo(nowhere)
                }
            }
            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(blockAt(tntSpawn.copy(y = 64.0))).`as`("marker removed").isEqualTo("minecraft:grass_block")
        }
    }

    @Test
    fun `carry tnt - a dropped tnt returns to the last checkpoint reached`() {
        scenario("siege-carry-tnt-drop-return") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-tnt")
            takeTnt(runner)
            hold(runner, blueRoute.first(), 80)
            guard.holds("pistol")
            guard.moveTo(blueRoute.first().copy(z = blueRoute.first().z + 8.0))
            kill(guard, runner)
            assertThat(sim.objectiveStage.markers(matchId).keys).`as`("dropped TNT is marked").isNotEmpty()
            guard.moveTo(nowhere)
            // drop_return_seconds (12 s): nobody takes it, it goes to the last checkpoint and waits there with no timer.
            advance(20 * 14)
            respawnAt(runner, nowhere)
            hold(runner, blueRoute.first(), 60)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("the TNT waits at the checkpoint").isNotNull()
        }
    }

    // ---- capture-wool -----------------------------------------------------

    @Test
    fun `capture wool - a contested zone does not progress, a captured zone can be retaken, holding the majority for the hold time wins`() {
        scenario("siege-capture-wool") {
            val holder = player("holder", at = nowhere)
            val rival = player("rival", at = nowhere)
            val matchId = startMatch("capture-wool", mapOf(holder to "red", rival to "blue"))
            val woolA = Vec3(0.0, 64.0, -30.0)
            val woolB = Vec3(0.0, 64.0, 30.0)
            assertThat(blockAt(woolA)).isEqualTo("minecraft:white_wool")
            assertThat(blockAt(woolA.copy(x = 2.0))).`as`("the pad is 5x5: concrete ring").isEqualTo("minecraft:light_gray_concrete")

            // Both stand in wool-a for longer than the capture time: nobody captures.
            holder.moveTo(Vec3(0.0, 65.0, -30.0))
            rival.moveTo(Vec3(1.0, 65.0, -29.0))
            advance(20 * 14)
            assertThat(blockAt(woolA)).isEqualTo("minecraft:white_wool")
            assertThat(sim.objectiveStage.bar(holder.id, "objective")?.progress).`as`("contested: the bar shows no progress").isEqualTo(0.0)

            // Rival leaves: the holder captures wool-a after 10 s alone; the pad turns red.
            rival.moveTo(nowhere)
            advance(20 * 4)
            assertThat(sim.objectiveStage.bar(holder.id, "objective")?.progress).`as`("alone: the capture bar fills").isGreaterThan(0.0)
            assertThat(advanceUntil(20 * 12) { blockAt(woolA) == "minecraft:red_wool" }).isTrue()
            assertThat(blockAt(woolA.copy(x = 2.0))).isEqualTo("minecraft:red_concrete")
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // The rival retakes it alone (8 s): the pad turns blue.
            holder.moveTo(nowhere)
            rival.moveTo(Vec3(0.0, 65.0, -30.0))
            assertThat(advanceUntil(20 * 12) { blockAt(woolA) == "minecraft:blue_wool" }).`as`("a captured zone can be retaken").isTrue()
            rival.moveTo(nowhere)

            // The holder takes both zones: majority held, 20 s later the match ends.
            holder.moveTo(Vec3(0.0, 65.0, -30.0))
            assertThat(advanceUntil(20 * 12) { blockAt(woolA) == "minecraft:red_wool" }).isTrue()
            holder.moveTo(Vec3(0.0, 65.0, 30.0))
            assertThat(advanceUntil(20 * 14) { blockAt(woolB) == "minecraft:red_wool" }).isTrue()
            assertThat(eventsOf<MatchEnded>()).`as`("holding the majority is not an instant win").isEmpty()
            assertThat(advanceUntil(20 * 25) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()

            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(blockAt(woolA)).`as`("markers restored at match end").isEqualTo("minecraft:grass_block")
        }
    }

    // ---- steal-jewelry ----------------------------------------------------

    @Test
    fun `steal jewelry - a dead thief drops the jewels, the owners recover them, banking every enemy jewel wins`() {
        scenario("siege-steal-jewelry") {
            val thief = player("thief", at = nowhere)
            val keeper = player("keeper", at = nowhere)
            val matchId = startMatch("steal-jewelry", mapOf(thief to "red", keeper to "blue"), stage = "siege-isles-jewelry")
            val bluePedestal = Vec3(53.0, 65.0, 10.0)
            val pedestalMarker = Vec3(53.0, 64.0, 10.0)
            assertThat(blockAt(pedestalMarker)).isEqualTo("minecraft:diamond_block")

            // Thief steals a jewel (a 2.5 s hold); the keeper shoots them down before banking and the jewel lies where it fell.
            keeper.holds("pistol")
            hold(thief, bluePedestal, 70)
            assertThat(sim.objectiveStage.carrying(thief.id)).`as`("thief wears a jewel").isNotNull()
            keeper.moveTo(Vec3(53.0, 65.0, 20.0))
            kill(keeper, thief)
            assertThat(sim.objectiveStage.carrying(thief.id)).isNull()
            assertThat(sim.objectiveStage.markers(matchId).keys.filter { it.startsWith("pile-") }).`as`("jewels on the ground").isNotEmpty()

            // The keeper's team recovers its own jewels by standing on the pile; nothing was banked.
            hold(keeper, bluePedestal)
            assertThat(sim.objectiveStage.markers(matchId).keys.filter { it.startsWith("pile-") }).`as`("pile recovered").isEmpty()
            respawnAt(thief, nowhere)
            hold(thief, redBase)
            assertThat(eventsOf<MatchEnded>()).`as`("nothing was banked").isEmpty()

            // Now the thief steals every jewel (3 per team) and banks them.
            keeper.moveTo(nowhere)
            repeat(3) {
                hold(thief, bluePedestal, 70)
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
    fun `steal jewelry - the steal takes 2 and a half seconds and damage interrupts it`() {
        scenario("siege-steal-jewelry-channel") {
            val thief = player("thief", at = nowhere)
            val keeper = player("keeper", at = nowhere)
            startMatch("steal-jewelry", mapOf(thief to "red", keeper to "blue"), stage = "siege-isles-jewelry")
            val bluePedestal = Vec3(53.0, 65.0, 10.0)

            thief.moveTo(bluePedestal)
            advance(30)
            assertThat(sim.objectiveStage.carrying(thief.id)).`as`("1.5 s is not enough").isNull()
            assertThat(sim.objectiveStage.bar(thief.id, "channel")?.progress ?: 0.0).`as`("the steal bar fills").isGreaterThan(0.0)
            // One hit cancels the hold; the bar starts over.
            keeper.holds("pistol")
            keeper.moveTo(Vec3(53.0, 65.0, 20.0))
            keeper.faces(thief)
            keeper.rightClick()
            check(advanceUntil(40) { eventsOf<DamageDealt>().any { it.target == thief.id } }) { "the keeper never hit the thief" }
            advance(30)
            assertThat(sim.objectiveStage.carrying(thief.id)).`as`("the interrupted hold restarted from zero").isNull()
            keeper.moveTo(nowhere)
            check(advanceUntil(120) { sim.objectiveStage.carrying(thief.id) != null }) { "an undisturbed hold completes" }
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

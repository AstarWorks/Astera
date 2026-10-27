package com.astarworks.astera.gamesim

import com.astarworks.astera.bots.ModePlanner
import com.astarworks.astera.bots.StageObjectives
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

    @Test
    fun `carry tnt - three deliveries (-34 each) knock out, a dead carrier drops the tnt where it fell`() {
        scenario("siege-carry-tnt") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-tnt")
            assertThat(blockAt(tntSpawn.copy(y = 64.0))).isEqualTo("minecraft:tnt")

            // Delivering without the TNT scores nothing.
            hold(runner, blueBase)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // Runner grabs the TNT (it is a hold, not a touch), glows and is slowed; the guard shoots them down.
            guard.holds("pistol")
            runner.moveTo(tntSpawn)
            advance(5)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("a brief touch does not pick up").isNull()
            advance(40)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("carrier wears the TNT").isNotNull()
            guard.moveTo(Vec3(0.0, 65.0, -14.0))
            kill(guard, runner)
            assertThat(sim.objectiveStage.carrying(runner.id)).`as`("dead carrier is stripped").isNull()
            assertThat(sim.objectiveStage.markers(matchId).keys).`as`("the dropped TNT is marked").isNotEmpty()
            respawnAt(runner, nowhere)

            // The guard (blue) picks the dropped TNT up and delivers it to the red base: blue count 66.
            hold(guard, tntSpawn)
            assertThat(sim.objectiveStage.carrying(guard.id)).isNotNull()
            hold(guard, redBase)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // Runner now carries it to the blue base three times (100 -> 66 -> 32 -> 0).
            repeat(3) {
                hold(runner, tntSpawn)
                hold(runner, blueBase)
                if (it < 2) {
                    assertThat(eventsOf<MatchEnded>()).`as`("red still above 0").isEmpty()
                    runner.moveTo(nowhere)
                }
            }
            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(blockAt(tntSpawn.copy(y = 64.0))).`as`("marker removed").isEqualTo("minecraft:grass_block")
        }
    }

    // ---- capture-wool -----------------------------------------------------

    @Test
    fun `capture wool - a contested zone does not progress, holding all three zones knocks out`() {
        scenario("siege-capture-wool") {
            val holder = player("holder", at = nowhere)
            val rival = player("rival", at = nowhere)
            val matchId = startMatch("capture-wool", mapOf(holder to "red", rival to "blue"))
            val woolA = Vec3(0.0, 64.0, -30.0)
            val woolB = Vec3(0.0, 64.0, 30.0)
            assertThat(blockAt(woolA)).isEqualTo("minecraft:white_wool")

            // Both stand in wool-a for longer than the capture time: nobody captures.
            holder.moveTo(Vec3(0.0, 65.0, -30.0))
            rival.moveTo(Vec3(1.0, 65.0, -29.0))
            advance(20 * 14)
            assertThat(blockAt(woolA)).isEqualTo("minecraft:white_wool")
            assertThat(sim.objectiveStage.bar(holder.id, "objective")?.progress).`as`("contested: the bar shows no progress").isEqualTo(0.0)

            // Rival leaves: the holder captures wool-a after 10 s alone.
            rival.moveTo(nowhere)
            advance(20 * 4)
            assertThat(sim.objectiveStage.bar(holder.id, "objective")?.progress).`as`("alone: the capture bar fills").isGreaterThan(0.0)
            assertThat(advanceUntil(20 * 12) { blockAt(woolA) == "minecraft:red_wool" }).isTrue()
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            holder.moveTo(Vec3(0.0, 65.0, 30.0))
            assertThat(advanceUntil(20 * 12) { blockAt(Vec3(0.0, 64.0, 30.0)) == "minecraft:red_wool" }).isTrue()
            assertThat(eventsOf<MatchEnded>()).`as`("two of three zones: count 32, no knockout").isEmpty()

            holder.moveTo(Vec3(0.0, 65.0, 6.0))
            assertThat(advanceUntil(20 * 12) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()

            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(blockAt(woolA)).`as`("markers restored at match end").isEqualTo("minecraft:grass_block")
        }
    }

    // ---- steal-jewelry ----------------------------------------------------

    @Test
    fun `steal jewelry - a dead thief drops the jewels, the owners recover them, banking three jewels knocks out`() {
        scenario("siege-steal-jewelry") {
            val thief = player("thief", at = nowhere)
            val keeper = player("keeper", at = nowhere)
            val matchId = startMatch("steal-jewelry", mapOf(thief to "red", keeper to "blue"), stage = "siege-isles-jewelry")
            val bluePedestal = Vec3(37.0, 65.0, 8.0)
            val pedestalMarker = Vec3(37.0, 64.0, 8.0)
            assertThat(blockAt(pedestalMarker)).isEqualTo("minecraft:diamond_block")

            // Thief steals jewels; the keeper shoots them down before banking and the jewels lie where they fell.
            keeper.holds("pistol")
            hold(thief, bluePedestal)
            assertThat(sim.objectiveStage.carrying(thief.id)).`as`("thief wears a jewel").isNotNull()
            keeper.moveTo(Vec3(37.0, 65.0, 18.0))
            kill(keeper, thief)
            assertThat(sim.objectiveStage.carrying(thief.id)).isNull()
            assertThat(sim.objectiveStage.markers(matchId).keys.filter { it.startsWith("pile-") }).`as`("jewels on the ground").isNotEmpty()

            // The keeper's team recovers its own jewels by standing on the pile; nothing was banked.
            hold(keeper, bluePedestal)
            assertThat(sim.objectiveStage.markers(matchId).keys.filter { it.startsWith("pile-") }).`as`("pile recovered").isEmpty()
            respawnAt(thief, nowhere)
            hold(thief, redBase)
            assertThat(eventsOf<MatchEnded>()).`as`("nothing was banked").isEmpty()

            // Now the thief steals every jewel and banks them.
            keeper.moveTo(nowhere)
            repeat(3) {
                hold(thief, bluePedestal)
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

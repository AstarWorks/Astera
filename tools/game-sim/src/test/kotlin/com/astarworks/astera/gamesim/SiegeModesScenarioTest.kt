package com.astarworks.astera.gamesim

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
    private val redCore = Vec3(-90.0, 65.0, 0.0)
    private val blueCore = Vec3(90.0, 65.0, 0.0)
    private val tntSpawn = Vec3(0.0, 65.0, -6.0)
    private val redBase = Vec3(-85.0, 65.0, 0.0)
    private val blueBase = Vec3(85.0, 65.0, 0.0)
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

    // ---- break-core -------------------------------------------------------

    @Test
    fun `break core - destroying the enemy core wins, your own core cannot be broken`() {
        scenario("siege-break-core") {
            val attacker = player("attacker")
            val defender = player("defender")
            val matchId = startMatch("break-core", mapOf(attacker to "red", defender to "blue"))

            assertThat(blockAt(redCore)).isEqualTo("minecraft:emerald_block")
            assertThat(blockAt(blueCore)).isEqualTo("minecraft:emerald_block")
            assertThat(attacker.position).`as`("spawned on a red spawn").isIn(
                Vec3(-51.5, 65.0, -2.5), Vec3(-51.5, 65.0, 3.5), Vec3(-49.5, 65.0, 0.5), Vec3(-53.5, 65.0, 0.5),
            )

            // Own core: block comes back, match goes on.
            attacker.breaks(redCore)
            assertThat(blockAt(redCore)).isEqualTo("minecraft:emerald_block")
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // A plain block next to the enemy core does not count.
            attacker.breaks(Vec3(90.0, 64.0, 0.0))
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            attacker.breaks(blueCore)
            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(end.reason).isEqualTo(EndReason.TeamVictory(TeamId("red")))
            assertThat(phaseOf(matchId)).isEqualTo(MatchPhase.ENDED)
            assertThat(blockAt(redCore)).`as`("core markers are removed at match end").isNotEqualTo("minecraft:emerald_block")
            assertThat(blockAt(blueCore)).isNotEqualTo("minecraft:emerald_block")
        }
    }

    // ---- carry-tnt --------------------------------------------------------

    @Test
    fun `carry tnt - two deliveries win, a dead carrier returns the tnt`() {
        scenario("siege-carry-tnt") {
            val runner = player("runner", at = nowhere)
            val guard = player("guard", at = nowhere)
            val matchId = startMatch("carry-tnt", mapOf(runner to "red", guard to "blue"), stage = "siege-isles-tnt")
            assertThat(blockAt(tntSpawn.copy(y = 64.0))).isEqualTo("minecraft:tnt")

            // Delivering without the TNT scores nothing.
            runner.moveTo(blueBase)
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // Runner grabs the TNT and is shot down: the TNT is back, so the guard can take it.
            guard.holds("pistol")
            runner.moveTo(tntSpawn)
            guard.moveTo(Vec3(0.0, 65.0, -14.0))
            kill(guard, runner)
            respawnAt(runner, nowhere)
            guard.moveTo(tntSpawn)
            guard.moveTo(redBase) // the guard (blue) delivers: 1/2
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            // Runner now carries it to the blue base twice.
            repeat(2) {
                runner.moveTo(tntSpawn)
                runner.moveTo(blueBase)
                if (it == 0) {
                    assertThat(eventsOf<MatchEnded>()).`as`("red 1/2, blue 1/2").isEmpty()
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
    fun `capture wool - a contested zone does not progress, holding two zones wins`() {
        scenario("siege-capture-wool") {
            val holder = player("holder", at = nowhere)
            val rival = player("rival", at = nowhere)
            val matchId = startMatch("capture-wool", mapOf(holder to "red", rival to "blue"))
            val woolA = Vec3(0.0, 64.0, -62.0)
            val woolB = Vec3(0.0, 64.0, 62.0)
            assertThat(blockAt(woolA)).isEqualTo("minecraft:white_wool")

            // Both stand in wool-a for longer than the capture time: nobody captures.
            holder.moveTo(Vec3(0.0, 65.0, -62.0))
            rival.moveTo(Vec3(1.0, 65.0, -61.0))
            advance(20 * 14)
            assertThat(blockAt(woolA)).isEqualTo("minecraft:white_wool")

            // Rival leaves: the holder captures wool-a after 10 s alone.
            rival.moveTo(nowhere)
            assertThat(advanceUntil(20 * 12) { blockAt(woolA) == "minecraft:red_wool" }).isTrue()
            assertThat(eventsOf<MatchEnded>()).isEmpty()

            holder.moveTo(Vec3(0.0, 65.0, 62.0))
            assertThat(advanceUntil(20 * 12) { eventsOf<MatchEnded>().isNotEmpty() }).isTrue()

            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(blockAt(woolA)).`as`("markers restored at match end").isEqualTo("minecraft:grass_block")
        }
    }

    // ---- steal-jewelry ----------------------------------------------------

    @Test
    fun `steal jewelry - a dead thief returns the jewels, banking every enemy jewel wins`() {
        scenario("siege-steal-jewelry") {
            val thief = player("thief", at = nowhere)
            val keeper = player("keeper", at = nowhere)
            val matchId = startMatch("steal-jewelry", mapOf(thief to "red", keeper to "blue"), stage = "siege-isles-jewelry")
            val bluePedestal = Vec3(73.0, 65.0, 10.0)
            val pedestalMarker = Vec3(73.0, 64.0, 10.0)
            assertThat(blockAt(pedestalMarker)).isEqualTo("minecraft:diamond_block")

            // Thief steals one jewel, the keeper shoots them down before banking.
            keeper.holds("pistol")
            thief.moveTo(bluePedestal)
            keeper.moveTo(Vec3(73.0, 65.0, 20.0))
            kill(keeper, thief)
            respawnAt(thief, nowhere)
            thief.moveTo(redBase)
            assertThat(eventsOf<MatchEnded>()).`as`("nothing was banked").isEmpty()

            // The jewel went back: both can be stolen (leave the pedestal in between) and banked.
            keeper.moveTo(nowhere)
            repeat(2) {
                thief.moveTo(bluePedestal)
                thief.moveTo(nowhere)
            }
            assertThat(eventsOf<MatchEnded>()).isEmpty()
            thief.moveTo(redBase)

            val end = ended()
            assertThat(end.matchId).isEqualTo(matchId)
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(blockAt(pedestalMarker)).isEqualTo("minecraft:grass_block")
        }
    }

    // ---- battle-loyal -----------------------------------------------------

    @Test
    fun `battle loyal - the first team to five kills wins and the score is mirrored`() {
        scenario("siege-battle-loyal") {
            val ace = player("ace", at = Vec3(0.0, groundY, 0.0))
            val prey = player("prey", at = Vec3(0.0, groundY, 10.0))
            val matchId = startMatch("battle-loyal", mapOf(ace to "red", prey to "blue"), stage = "siege-isles-loyal")
            ace.position = Vec3(0.0, groundY, 0.0)
            prey.position = Vec3(0.0, groundY, 10.0)
            ace.holds("pistol")

            // A kill only scores when the killer is credited: a burn that finishes the victim
            // off has no origin, so more than five deaths may be needed.
            var previous = 0
            var rounds = 0
            while (eventsOf<MatchEnded>().isEmpty() && rounds++ < 15) {
                kill(ace, prey)
                val score = sim.services.getOrThrow<IMatchRepository>().findById(matchId)!!.score.valueFor(TeamId("red"))
                assertThat(score).isBetween(previous, previous + 1)
                previous = score
                if (eventsOf<MatchEnded>().isEmpty()) respawnAt(prey, Vec3(0.0, groundY, 10.0))
            }
            assertThat(previous).isEqualTo(5)
            val end = ended()
            assertThat(end.winningTeam).isEqualTo(TeamId("red"))
            assertThat(end.reason).isEqualTo(EndReason.TeamVictory(TeamId("red")))
        }
    }

    // ---- queue lanes ------------------------------------------------------

    @ParameterizedTest(name = "lane {0} forms a match on its own stage for two queued players")
    @ValueSource(strings = ["break-core", "carry-tnt", "capture-wool", "steal-jewelry", "battle-loyal", "carry-mini-tnt"])
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
            // MODE-06 / ADR-0281: break-core and capture-wool run on imported worlds, the other lanes on their own siege-isles copy.
            val stage = when (lane) {
                "break-core" -> "medieval-village"
                "capture-wool" -> "temple-trouble"
                else -> "siege-isles-${lane.removePrefix("carry-").removePrefix("steal-").removePrefix("battle-")}"
            }
            assertThat(started.stageId).isEqualTo(StageId(stage))
            // The 10 s in-arena countdown ends and the match is live.
            assertThat(advanceUntil(20 * 15) { phaseOf(started.matchId) == MatchPhase.ACTIVE }).isTrue()
        }
    }
}

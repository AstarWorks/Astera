package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.SpawnRole
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [DefaultSpawnSelector] (ADR-0081; per-team spawns in WP9):
 * team resolution for 2 and 3 teams, every [SpawnStrategy], and spreading.
 */
class DefaultSpawnSelectorTest {

    private val world = WorldId("world")
    private val matchId = MatchId("match-1")
    private val red = TeamId("red")
    private val blue = TeamId("blue")
    private val yellow = TeamId("yellow")
    private val p1 = player(1)
    private val p2 = player(2)
    private val p3 = player(3)
    private val p4 = player(4)

    private val redA = spawn(red, Vec3(0.0, 64.0, 0.0))
    private val redB = spawn(red, Vec3(10.0, 64.0, 0.0))
    private val redC = spawn(red, Vec3(20.0, 64.0, 0.0))
    private val blueA = spawn(blue, Vec3(0.0, 64.0, 50.0))
    private val yellowA = spawn(yellow, Vec3(0.0, 64.0, -50.0))

    private lateinit var matches: InMemoryMatchRepository

    @BeforeEach
    fun setUp() {
        matches = InMemoryMatchRepository()
        // p1,p2,p3 -> red, p4 -> blue
        matches.save(matchWith(red to setOf(p1, p2, p3), blue to setOf(p4), yellow to emptySet()))
    }

    @Test
    fun `each team gets a spawn of its own team (three teams)`() {
        val selector = DefaultSpawnSelector(matches)
        val stage = stageOf(redA, blueA, yellowA)
        matches.save(matchWith(red to setOf(p1), blue to setOf(p2), yellow to setOf(p3)))

        assertThat(selector.selectFor(p1, matchId, stage, SpawnStrategy.Spread)).isEqualTo(redA)
        assertThat(selector.selectFor(p2, matchId, stage, SpawnStrategy.Spread)).isEqualTo(blueA)
        assertThat(selector.selectFor(p3, matchId, stage, SpawnStrategy.Spread)).isEqualTo(yellowA)
    }

    @Test
    fun `Spread hands out distinct spawns to teammates before reusing one`() {
        val selector = DefaultSpawnSelector(matches)
        val stage = stageOf(redA, redB, redC, blueA)

        val picks = listOf(p1, p2, p3, p1).map { selector.selectFor(it, matchId, stage, SpawnStrategy.Spread) }

        assertThat(picks.take(3)).containsExactly(redA, redB, redC)
        assertThat(picks[3]).isEqualTo(redA) // all used once -> wraps to declaration order
    }

    @Test
    fun `forget resets the spread counters of a match`() {
        val selector = DefaultSpawnSelector(matches)
        val stage = stageOf(redA, redB)
        selector.selectFor(p1, matchId, stage, SpawnStrategy.Spread)
        selector.forget(matchId)

        assertThat(selector.selectFor(p2, matchId, stage, SpawnStrategy.Spread)).isEqualTo(redA)
    }

    @Test
    fun `FirstAvailable picks the first spawn of the team in declaration order`() {
        val selector = DefaultSpawnSelector(matches)
        val stage = stageOf(blueA, redA, redB, redC)

        assertThat(selector.selectFor(p1, matchId, stage, SpawnStrategy.FirstAvailable)).isEqualTo(redA)
    }

    @Test
    fun `Random picks one of the team's spawns`() {
        val selector = DefaultSpawnSelector(matches, random = kotlin.random.Random(0))
        val stage = stageOf(redA, redB, redC, blueA)

        assertThat(selector.selectFor(p1, matchId, stage, SpawnStrategy.Random)).isIn(redA, redB, redC)
    }

    @Test
    fun `CycleRotation advances by one slot and wraps on overflow`() {
        val selector = DefaultSpawnSelector(matches)
        val stage = stageOf(redA, redB, redC)

        assertThat(selector.selectFor(p1, matchId, stage, SpawnStrategy.CycleRotation(0))).isEqualTo(redB)
        assertThat(selector.selectFor(p1, matchId, stage, SpawnStrategy.CycleRotation(2))).isEqualTo(redA)
        assertThat(selector.selectFor(p1, matchId, stage, SpawnStrategy.CycleRotation(-1))).isEqualTo(redA)
    }

    @Test
    fun `FurthestFromEnemies picks the spawn maximising min-distance to enemies`() {
        val selector = DefaultSpawnSelector(
            matches,
            enemyPositionsProvider = { _, _ -> listOf(Vec3(0.0, 64.0, 0.0)) },
        )
        val stage = stageOf(redA, redB, redC)

        val picked = selector.selectFor(p1, matchId, stage, SpawnStrategy.FurthestFromEnemies(safeRadius = 5.0))
        assertThat(picked).isEqualTo(redC)
    }

    @Test
    fun `FurthestFromEnemies degenerates to FirstAvailable when no enemies provided`() {
        val selector = DefaultSpawnSelector(matches)
        val stage = stageOf(redA, redB, redC)

        val picked = selector.selectFor(p1, matchId, stage, SpawnStrategy.FurthestFromEnemies(safeRadius = 5.0))
        assertThat(picked).isEqualTo(redA)
    }

    @Test
    fun `FurthestFromEnemies still picks an unsafe spawn when every candidate is inside safeRadius`() {
        val a = spawn(red, Vec3(1.0, 64.0, 0.0))
        val b = spawn(red, Vec3(2.0, 64.0, 0.0))
        val selector = DefaultSpawnSelector(
            matches,
            enemyPositionsProvider = { _, _ -> listOf(Vec3(0.0, 64.0, 0.0)) },
        )

        val picked = selector.selectFor(p1, matchId, stageOf(a, b), SpawnStrategy.FurthestFromEnemies(safeRadius = 100.0))
        assertThat(picked).isEqualTo(b)
    }

    @Test
    fun `returns null when the stage has no spawn for the player's team`() {
        val selector = DefaultSpawnSelector(matches)

        assertThat(selector.selectFor(p1, matchId, stageOf(blueA), SpawnStrategy.FirstAvailable)).isNull()
    }

    @Test
    fun `a player without a team gets a lobby spawn`() {
        val selector = DefaultSpawnSelector(matches)
        val lobby = SpawnPoint(world, Vec3(0.0, 80.0, 0.0), role = SpawnRole.LOBBY)
        val stage = stageOf(redA, lobby)

        assertThat(selector.selectFor(player(99), matchId, stage, SpawnStrategy.FirstAvailable)).isEqualTo(lobby)
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private fun player(n: Int) = PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000%02x".format(n)))

    private fun spawn(team: TeamId, pos: Vec3): SpawnPoint =
        SpawnPoint(world = world, position = pos, role = SpawnRole.TEAM, team = team)

    private fun stageOf(vararg spawns: SpawnPoint): Stage = Stage(
        id = StageId("test"),
        displayNameKey = MessageKey("astera.stage.test.name"),
        regions = emptyList(),
        spawnPoints = spawns.toList(),
    )

    private fun matchWith(vararg teams: Pair<TeamId, Set<PlayerId>>): Match = Match(
        id = matchId,
        phase = MatchPhase.COUNTDOWN,
        teams = teams.map { (id, members) ->
            Team(
                id = id,
                displayNameKey = MessageKey("astera.match.team.${id.value}"),
                colorKey = MessageKey("astera.match.team.${id.value}.color"),
                members = members,
            )
        },
        score = Score(teams.associate { it.first to 0 }),
        stageId = StageId("test"),
        startedAtTick = 0L,
    )
}

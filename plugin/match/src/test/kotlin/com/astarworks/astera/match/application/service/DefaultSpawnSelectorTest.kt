package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.application.port.outbound.IMatchTeamLookup
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.SpawnFaction
import com.astarworks.astera.core.domain.stage.SpawnPoint
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [DefaultSpawnSelector] (Wave 5o, ADR-0081).
 *
 * Covers every [SpawnStrategy] variant + faction resolution via the
 * caller-supplied team-id → faction map.
 */
class DefaultSpawnSelectorTest {

    private val world = WorldId("world")
    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"))
    private val matchId = MatchId("match-1")

    private val attackerA = spawn(SpawnFaction.ATTACKER, Vec3(0.0, 64.0, 0.0))
    private val attackerB = spawn(SpawnFaction.ATTACKER, Vec3(10.0, 64.0, 0.0))
    private val attackerC = spawn(SpawnFaction.ATTACKER, Vec3(20.0, 64.0, 0.0))
    private val defenderA = spawn(SpawnFaction.DEFENDER, Vec3(0.0, 64.0, 50.0))

    @Test
    fun `FirstAvailable picks the first matching spawn in declaration order`() {
        val selector = DefaultSpawnSelector(
            matchTeamLookup = fixedTeam(TeamId("red")),
        )
        val stage = stageOf(attackerA, attackerB, attackerC, defenderA)

        val picked = selector.selectFor(playerId, matchId, stage, SpawnStrategy.FirstAvailable)
        assertThat(picked).isEqualTo(attackerA)
    }

    @Test
    fun `Random picks deterministically with a seeded Random`() {
        // Seed 0 chooses an index from {0..2}; we don't pin the exact pick,
        // just assert it's one of the attacker candidates.
        val selector = DefaultSpawnSelector(
            matchTeamLookup = fixedTeam(TeamId("red")),
            random = kotlin.random.Random(0),
        )
        val stage = stageOf(attackerA, attackerB, attackerC, defenderA)
        val picked = selector.selectFor(playerId, matchId, stage, SpawnStrategy.Random)
        assertThat(picked).isIn(attackerA, attackerB, attackerC)
    }

    @Test
    fun `CycleRotation advances by one slot and wraps on overflow`() {
        val selector = DefaultSpawnSelector(matchTeamLookup = fixedTeam(TeamId("red")))
        val stage = stageOf(attackerA, attackerB, attackerC)

        assertThat(selector.selectFor(playerId, matchId, stage, SpawnStrategy.CycleRotation(0)))
            .isEqualTo(attackerB)
        assertThat(selector.selectFor(playerId, matchId, stage, SpawnStrategy.CycleRotation(2)))
            .isEqualTo(attackerA) // wraps
        assertThat(selector.selectFor(playerId, matchId, stage, SpawnStrategy.CycleRotation(-1)))
            .isEqualTo(attackerA) // -1 + 1 = 0 mod 3
    }

    @Test
    fun `FurthestFromEnemies picks the spawn maximising min-distance to enemies`() {
        val selector = DefaultSpawnSelector(
            matchTeamLookup = fixedTeam(TeamId("red")),
            enemyPositionsProvider = { _, _ -> listOf(Vec3(0.0, 64.0, 0.0)) },
        )
        val stage = stageOf(attackerA, attackerB, attackerC)

        // Enemy at attackerA's position → safest spawn is attackerC (furthest).
        val picked = selector.selectFor(
            playerId, matchId, stage,
            SpawnStrategy.FurthestFromEnemies(safeRadius = 5.0),
        )
        assertThat(picked).isEqualTo(attackerC)
    }

    @Test
    fun `FurthestFromEnemies degenerates to FirstAvailable when no enemies provided`() {
        val selector = DefaultSpawnSelector(matchTeamLookup = fixedTeam(TeamId("red")))
        val stage = stageOf(attackerA, attackerB, attackerC)
        val picked = selector.selectFor(
            playerId, matchId, stage,
            SpawnStrategy.FurthestFromEnemies(safeRadius = 5.0),
        )
        assertThat(picked).isEqualTo(attackerA)
    }

    @Test
    fun `returns null when no spawn matches the player's faction`() {
        val selector = DefaultSpawnSelector(matchTeamLookup = fixedTeam(TeamId("red")))
        val stage = stageOf(defenderA) // no attacker spawn

        val picked = selector.selectFor(playerId, matchId, stage, SpawnStrategy.FirstAvailable)
        assertThat(picked).isNull()
    }

    @Test
    fun `falls back to LOBBY faction when no match-team lookup is wired`() {
        val selector = DefaultSpawnSelector(matchTeamLookup = null)
        val lobby = spawn(SpawnFaction.LOBBY, Vec3(0.0, 80.0, 0.0))
        val stage = stageOf(attackerA, lobby)

        val picked = selector.selectFor(playerId, matchId, stage, SpawnStrategy.FirstAvailable)
        assertThat(picked).isEqualTo(lobby)
    }

    @Test
    fun `FurthestFromEnemies still picks an unsafe spawn when every candidate is inside safeRadius`() {
        // Enemy at origin, both spawns within the safe radius -> Annihilation
        // rule: never deny respawn, pick the furthest one anyway.
        val a = spawn(SpawnFaction.ATTACKER, Vec3(1.0, 64.0, 0.0))
        val b = spawn(SpawnFaction.ATTACKER, Vec3(2.0, 64.0, 0.0))
        val selector = DefaultSpawnSelector(
            matchTeamLookup = fixedTeam(TeamId("red")),
            enemyPositionsProvider = { _, _ -> listOf(Vec3(0.0, 64.0, 0.0)) },
        )
        val stage = stageOf(a, b)
        val picked = selector.selectFor(
            playerId, matchId, stage,
            SpawnStrategy.FurthestFromEnemies(safeRadius = 100.0),
        )
        assertThat(picked).isEqualTo(b)
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private fun spawn(faction: SpawnFaction, pos: Vec3): SpawnPoint =
        SpawnPoint(world = world, position = pos, faction = faction)

    private fun stageOf(vararg spawns: SpawnPoint): Stage = Stage(
        id = StageId("test"),
        displayNameKey = MessageKey("astera.stage.test.name"),
        regions = emptyList(),
        spawnPoints = spawns.toList(),
    )

    private fun fixedTeam(team: TeamId): IMatchTeamLookup = object : IMatchTeamLookup {
        override fun teamOf(playerId: PlayerId): TeamId? = team
    }
}

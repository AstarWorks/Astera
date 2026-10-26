package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.ArenaAllocationError
import com.astarworks.astera.core.application.port.outbound.IArenaBuilder
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import com.astarworks.astera.core.domain.proxy.ServerRole
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.matchmaking.IArrivalSpawns
import com.astarworks.astera.match.application.service.matchmaking.MatchAssignmentCodec
import com.astarworks.astera.match.domain.matchmaking.MatchAssignment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Bug #13: players fell into the void right after a match started. The game server used to place an arriving
 * player at the stage TEMPLATE's spawn (coordinates with no blocks) and only teleported them onto the built arena
 * slot once the match began. Now nobody is placed before a completed arena build exists, the arrival spawn is the
 * built slot's, the match start waits for readiness, and entrants take no damage until their entry teleport landed.
 */
class MatchStartArenaReadyScenarioTest {

    /** A controllable arena: nothing is placeable until [ready]; the built copy sits [SLOT_X] blocks east. */
    private class ReadinessArena : IArenaBuilder {
        @Volatile var ready = false
        var acquireAttempts = 0
        private val placedById = HashMap<StageId, Stage>()

        private fun placed(template: Stage): Stage {
            val id = StageId("${template.id.value}-slot-0")
            return placedById.getOrPut(id) {
                template.copy(id = id, spawnPoints = template.spawnPoints.map { it.copy(position = it.position.copy(x = it.position.x + SLOT_X)) })
            }
        }

        override fun peekReady(stage: Stage): Stage? = if (ready) placed(stage) else null
        override fun acquire(stage: Stage): Result<Stage, ArenaAllocationError> {
            acquireAttempts++
            return if (ready) Result.success(placed(stage)) else Result.failure(ArenaAllocationError.NotReady)
        }
        override fun resolve(stageId: StageId): Stage? = placedById[stageId]
        override fun ensureBuilt(stageId: StageId): Boolean = ready
        override fun repairLater(stageId: StageId) {}
    }

    @Test
    fun `nobody is placed before the arena is ready and nobody falls at match start`() {
        val arena = ReadinessArena()
        val network = SimProxyNetwork()
        val lobbyNode = network.node(ServerName("lobby"))
        val gameName = ServerName("game")
        val game = GameSim.boot("match-start-arena-ready", role = ServerRole.GAME, serverName = gameName, proxy = network.node(gameName), arenas = arena)
        network.register(gameName, game)
        try {
            val templateId = StageId("example-arena")
            val template = checkNotNull(game.services.getOrThrow<IStageRegistry>().find(templateId))
            val templateSpawns = template.spawnPoints.map { it.position }.toSet()
            val a = PlayerId(UUID.randomUUID())
            val b = PlayerId(UUID.randomUUID())
            lobbyNode.sendMessage(gameName, ProxyChannel.MATCH_FOUND,
                MatchAssignmentCodec.encode(MatchAssignment(MatchId("arena-ready"), templateId, null, listOf(listOf(a), listOf(b)))))
            network.pump()
            val spawns = game.services.getOrThrow<IArrivalSpawns>()

            // Arena still building: the login spawn is "unknown" (never the template's blockless coordinates).
            assertThat(spawns.spawnFor(a)).`as`("no arrival spawn while the arena is not ready").isNull()
            val ea = game.attachPlayer(a, "a")
            val eb = game.attachPlayer(b, "b")
            val resting = ea.position
            network.step(100)

            assertThat(game.eventsOf<MatchStarted>()).`as`("match start waits for the arena").isEmpty()
            assertThat(arena.acquireAttempts).`as`("start keeps retrying while not ready").isGreaterThan(1)
            assertThat(spawns.isWaiting(a)).`as`("arrived players stay protected").isTrue()
            for (e in listOf(ea, eb)) {
                assertThat(e.position).`as`("${e.name} untouched").isEqualTo(resting)
                assertThat(e.position).isNotIn(templateSpawns)
            }

            // The build completes: the arrival spawn is the BUILT slot's, then the match starts there.
            arena.ready = true
            val slotSpawn = checkNotNull(spawns.spawnFor(a)) { "ready arena must give an arrival spawn" }
            assertThat(slotSpawn.pos.x).`as`("spawn is in the built slot, not the template").isGreaterThanOrEqualTo(SLOT_X - 200.0)
            assertThat(slotSpawn.pos).isNotIn(templateSpawns)

            assertThat(game.stepUntil(100) { game.eventsOf<MatchStarted>().isNotEmpty() }).isTrue()
            val started = game.eventsOf<MatchStarted>().single()
            assertThat(started.stageId.value).endsWith("-slot-0")
            val match = checkNotNull(game.services.getOrThrow<IMatchRepository>().findById(started.matchId))
            assertThat(match.teams.flatMap { it.members }).containsExactlyInAnyOrder(a, b)
            val slotSpawns = checkNotNull(arena.resolve(started.stageId)).spawnPoints.map { it.position }.toSet()
            for (e in listOf(ea, eb)) {
                assertThat(e.position).`as`("${e.name} placed on the built slot").isIn(slotSpawns)
                assertThat(e.position.x).isGreaterThan(SLOT_X - 200.0)
            }

            // Landing grace: protected right after placement, released afterwards.
            assertThat(spawns.isWaiting(a)).`as`("protected while landing").isTrue()
            game.step(140)
            assertThat(spawns.isWaiting(a)).`as`("protection released after landing").isFalse()
        } finally {
            game.shutdown()
        }
    }

    private companion object {
        const val SLOT_X = 2048.0
    }
}

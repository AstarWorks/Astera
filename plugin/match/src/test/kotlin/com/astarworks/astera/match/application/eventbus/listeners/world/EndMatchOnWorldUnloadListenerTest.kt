package com.astarworks.astera.match.application.eventbus.listeners.world

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.WorldUnloading
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.application.service.InMemoryMatchWorldRepository
import com.astarworks.astera.match.application.usecase.match.EndMatchError
import com.astarworks.astera.match.application.usecase.match.EntryGameError
import com.astarworks.astera.match.application.usecase.match.StartMatchError
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseError
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.match.domain.match.MatchWorld
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave L 5hardening-complete (ADR-0129) — pins the WorldUnloading →
 * EndReason.WorldUnloaded fan-out.
 */
public class EndMatchOnWorldUnloadListenerTest {

    @Test
    public fun `WorldUnloading ends every match in the unloaded world`() {
        val matchA = MatchId("match-a")
        val matchB = MatchId("match-b")
        val worldA = WorldId("arena-1")
        val worldB = WorldId("arena-2")

        val repo = InMemoryMatchWorldRepository()
        repo.save(MatchWorld(matchA, worldA, "stages/arena-1.schem"))
        repo.save(MatchWorld(matchB, worldB, "stages/arena-2.schem"))

        val lifecycle = RecordingMatchLifecycle()
        val listener = EndMatchOnWorldUnloadListener(repo, lifecycle)

        listener.handle(WorldUnloading(worldId = worldA))

        assertThat(lifecycle.endedMatches)
            .`as`("only the match in the unloaded world should end")
            .containsExactly(matchA to EndReason.WorldUnloaded)
    }

    @Test
    public fun `WorldUnloading for a world with no active match is a no-op`() {
        val repo = InMemoryMatchWorldRepository()
        repo.save(MatchWorld(MatchId("lobby-match"), WorldId("lobby"), "lobby.schem"))

        val lifecycle = RecordingMatchLifecycle()
        val listener = EndMatchOnWorldUnloadListener(repo, lifecycle)

        listener.handle(WorldUnloading(worldId = WorldId("not-an-arena")))

        assertThat(lifecycle.endedMatches)
            .`as`("no matches in the unloaded world means no end call")
            .isEmpty()
    }

    @Test
    public fun `multiple matches in the same world all end`() {
        val world = WorldId("shared-world")
        val matchA = MatchId("shared-a")
        val matchB = MatchId("shared-b")

        val repo = InMemoryMatchWorldRepository()
        repo.save(MatchWorld(matchA, world, "shared.schem"))
        repo.save(MatchWorld(matchB, world, "shared.schem"))

        val lifecycle = RecordingMatchLifecycle()
        val listener = EndMatchOnWorldUnloadListener(repo, lifecycle)

        listener.handle(WorldUnloading(worldId = world))

        assertThat(lifecycle.endedMatches)
            .`as`("every match sharing the world must end")
            .containsExactlyInAnyOrder(
                matchA to EndReason.WorldUnloaded,
                matchB to EndReason.WorldUnloaded,
            )
    }

    @Test
    public fun `lifecycle end failures are swallowed and subsequent matches still end`() {
        val worldId = WorldId("arena-x")
        val matchA = MatchId("fail-a")
        val matchB = MatchId("ok-b")

        val repo = InMemoryMatchWorldRepository()
        repo.save(MatchWorld(matchA, worldId, "x.schem"))
        repo.save(MatchWorld(matchB, worldId, "x.schem"))

        val lifecycle = RecordingMatchLifecycle().apply {
            failOn += matchA
        }
        val listener = EndMatchOnWorldUnloadListener(repo, lifecycle)

        listener.handle(WorldUnloading(worldId = worldId))

        assertThat(lifecycle.endedMatches)
            .`as`("matchA returns Failure but matchB still ends — listener does not bail mid-loop")
            .contains(matchB to EndReason.WorldUnloaded)
    }

    @Test
    public fun `matchesIn filter returns only matches in the target world`() {
        val worldA = WorldId("arena-1")
        val worldB = WorldId("arena-2")

        val repo = InMemoryMatchWorldRepository()
        repo.save(MatchWorld(MatchId("m-a1"), worldA, "a.schem"))
        repo.save(MatchWorld(MatchId("m-a2"), worldA, "a.schem"))
        repo.save(MatchWorld(MatchId("m-b1"), worldB, "b.schem"))

        val listener = EndMatchOnWorldUnloadListener(repo, RecordingMatchLifecycle())

        assertThat(listener.matchesIn(worldA)).hasSize(2)
        assertThat(listener.matchesIn(worldB)).hasSize(1)
        assertThat(listener.matchesIn(WorldId("nowhere"))).isEmpty()
    }

    private class RecordingMatchLifecycle : IMatchLifecycle {
        val endedMatches: MutableList<Pair<MatchId, EndReason>> = mutableListOf()
        val failOn: MutableSet<MatchId> = mutableSetOf()

        override fun start(stageId: StageId, modeKey: String?): Result<MatchId, StartMatchError> =
            error("not used")

        override fun end(matchId: MatchId, reason: EndReason): Result<Unit, EndMatchError> {
            endedMatches += matchId to reason
            return if (matchId in failOn) {
                Result.failure(EndMatchError.MatchAlreadyEnded(matchId))
            } else {
                Result.success(Unit)
            }
        }

        override fun entry(
            matchId: MatchId,
            playerId: PlayerId,
            teamId: TeamId?,
        ): Result<Unit, EntryGameError> = error("not used")

        override fun switchPhase(
            matchId: MatchId,
            trigger: MatchStateMachine.TransitionTrigger,
        ): Result<MatchPhase, SwitchPhaseError> = error("not used")
    }
}

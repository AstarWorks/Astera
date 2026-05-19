package com.astarworks.astera.match.application.usecase.spawn

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerPortalReady
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId

/**
 * Teleport a player to a spawn point of [SpawnKind] for the match they belong
 * to.
 *
 * Phase A (Wave 4.5 / R3.5, ADR-0027) wires the use case end-to-end against
 * [IWorldGateway]. In Wave 4.5 the only [IWorldGateway] in production is
 * `FakeWorldGateway` (test-fixtures); the real `PaperWorldGateway.teleport`
 * lands in Wave 5 (R4 Paper runtime). The use case is therefore unit-testable
 * today and ships unchanged when Paper plumbing arrives.
 *
 * # Sequence
 * 1. Resolve the match by id ([SpawnError.MatchNotFound] otherwise).
 * 2. Resolve the stage referenced by `match.stageId`
 *    ([SpawnError.StageNotFound] otherwise — should be impossible in steady
 *    state but the repository contract permits it).
 * 3. Ask the [SpawnRotationStrategy] to pick a spawn of the requested [kind]
 *    ([SpawnError.NoSpawnPointForKind] if no spawn of that variant exists).
 * 4. Delegate the teleport to [IWorldGateway.teleport]
 *    ([SpawnError.TeleportFailed] if the gateway reports failure — typically
 *    "player offline" or "world unknown").
 *
 * The strategy defaults to [RandomSpawnRotationStrategy]; tests inject a
 * deterministic one if they need to assert on the picked position.
 */
public class MatchSpawnUseCase(
    private val matchRepository: IMatchRepository,
    private val stageRegistry: IStageRegistry,
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val strategy: SpawnRotationStrategy = RandomSpawnRotationStrategy(),
) {

    public fun spawnPlayer(
        matchId: MatchId,
        playerId: PlayerId,
        kind: SpawnKind,
    ): Result<Unit, SpawnError> {
        val match = matchRepository.findById(matchId)
            ?: return Result.failure(SpawnError.MatchNotFound(matchId))

        val stage = stageRegistry.find(match.stageId)
            ?: return Result.failure(SpawnError.StageNotFound(match.stageId))

        val spawnPoint = strategy.pick(stage.spawnPoints, kind)
            ?: return Result.failure(SpawnError.NoSpawnPointForKind(kind, stage.id))

        val ok = worldGateway.teleport(playerId, spawnPoint.pos)
        if (!ok) {
            return Result.failure(SpawnError.TeleportFailed(playerId))
        }

        // Wave 5e: signal cross-server portal-ready on the game-server entry side.
        // Reward bridge / audit listeners on the lobby side consume this when the
        // player reconnects after match rotation.
        eventBus.publish(PlayerPortalReady.EntryReady(playerId = playerId, matchId = matchId))

        return Result.success(Unit)
    }
}

/**
 * Expected failure modes of [MatchSpawnUseCase.spawnPlayer]. Each variant
 * carries an i18n [messageKey] so command / event handlers can surface a
 * localized message via `IMessageRenderer` without leaking domain detail.
 */
public sealed class SpawnError {
    public abstract val messageKey: MessageKey

    public data class MatchNotFound(val matchId: MatchId) : SpawnError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spawn.error.match-not-found")
    }

    public data class StageNotFound(val stageId: StageId) : SpawnError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spawn.error.stage-not-found")
    }

    public data class NoSpawnPointForKind(val kind: SpawnKind, val stageId: StageId) : SpawnError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spawn.error.no-spawn-for-kind")
    }

    public data class TeleportFailed(val playerId: PlayerId) : SpawnError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spawn.error.teleport-failed")
    }
}

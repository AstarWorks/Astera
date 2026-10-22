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
import com.astarworks.astera.match.application.service.ISpawnSelector
import com.astarworks.astera.match.application.service.SpawnStrategy

/**
 * Teleport a player to a spawn of their team in the match's stage (WP9).
 *
 * # Sequence
 * 1. Resolve the match ([SpawnError.MatchNotFound]) and its stage
 *    ([SpawnError.StageNotFound]).
 * 2. Ask the [ISpawnSelector] for a [SpawnStrategy.Spread] spawn of the
 *    player's team ([SpawnError.NoSpawnPoint] if the stage has none for it).
 * 3. Teleport into the spawn's world ([SpawnError.TeleportFailed] on refusal).
 *
 * Used for match entry; a player who enters after `MatchStarted` (phase
 * COUNTDOWN) goes through the same path, so late entrants are teleported too.
 */
public class MatchSpawnUseCase(
    private val matchRepository: IMatchRepository,
    private val stageRegistry: IStageRegistry,
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val spawnSelector: ISpawnSelector,
) {

    public fun spawnPlayer(
        matchId: MatchId,
        playerId: PlayerId,
    ): Result<Unit, SpawnError> {
        val match = matchRepository.findById(matchId)
            ?: return Result.failure(SpawnError.MatchNotFound(matchId))

        val stage = stageRegistry.find(match.stageId)
            ?: return Result.failure(SpawnError.StageNotFound(match.stageId))

        val spawnPoint = spawnSelector.selectFor(playerId, matchId, stage, SpawnStrategy.Spread)
            ?: return Result.failure(SpawnError.NoSpawnPoint(playerId, stage.id))

        val ok = worldGateway.teleport(playerId, spawnPoint.pos, spawnPoint.world, spawnPoint.yaw, spawnPoint.pitch)
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

    public data class NoSpawnPoint(val playerId: PlayerId, val stageId: StageId) : SpawnError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spawn.error.no-spawn-for-kind")
    }

    public data class TeleportFailed(val playerId: PlayerId) : SpawnError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spawn.error.teleport-failed")
    }
}

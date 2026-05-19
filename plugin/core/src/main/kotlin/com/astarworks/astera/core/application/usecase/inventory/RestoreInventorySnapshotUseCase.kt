package com.astarworks.astera.core.application.usecase.inventory

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerPortalReady
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState

/**
 * Restore a previously-saved [PlayerInventorySnapshot] for [playerId] (Wave 5e
 * / RTM issue #5).
 *
 * # Loading-state lifecycle
 *
 * Wave 6b's `BukkitEventAdapter` action-lock gates inventory-click / pickup /
 * interact events while the session is in [SessionState.Loading]. Wave 5e is
 * pre-Paper, so the lifecycle is observable but no MC-side effect runs:
 *
 * 1. **Enter Loading** — if a [PlayerSession] exists for [playerId], upsert
 *    `session.copy(state = Loading)`. Test stubs that capture every `upsert`
 *    can assert this happened mid-restore even though the call appears
 *    synchronous (the stub records the transition list).
 * 2. **Load snapshot** — call [IPlayerInventorySnapshotRepository.findLatest].
 *    Wave 6b will hand the snapshot to `PaperInventorySnapshotSerializer.apply`;
 *    Wave 5e returns it to the caller and the application is responsible.
 * 3. **Restore prior state** — if a session existed pre-restore, restore its
 *    prior [SessionState]. If no session existed, default to
 *    [SessionState.Lobby].
 * 4. **Publish [PlayerPortalReady.ExitReady]** — `returnToLobby = (priorState
 *    is Lobby)`. The reward bridge + audit log on the lobby-server side
 *    consume this for cross-server federation (ADR-0064).
 *
 * `Result.success(null)` is returned when no snapshot exists — that's the
 * expected first-join state for a brand-new player, not an error.
 */
public class RestoreInventorySnapshotUseCase(
    private val repo: IPlayerInventorySnapshotRepository,
    private val sessionRepo: IPlayerSessionRepository,
    private val eventBus: IDomainEventBus,
) {

    public fun restore(playerId: PlayerId): Result<PlayerInventorySnapshot?, InventorySnapshotError> {
        val priorSession: PlayerSession? = sessionRepo.find(playerId)
        val priorState: SessionState = priorSession?.state ?: SessionState.Lobby

        // 1. Enter Loading (only if a session already exists for the player).
        if (priorSession != null) {
            sessionRepo.upsert(priorSession.copy(state = SessionState.Loading))
        }

        // 2. Load snapshot. Repo failures (Postgres unavailable) surface as
        //    RestoreFailed; today's repos cannot raise but the catch keeps the
        //    use case forward-compatible with Wave 6b's real I/O paths.
        val snapshot: PlayerInventorySnapshot? = try {
            repo.findLatest(playerId)
        } catch (t: RuntimeException) {
            // Restore prior state before bailing so the player isn't stranded in Loading.
            if (priorSession != null) {
                sessionRepo.upsert(priorSession.copy(state = priorState))
            }
            return Result.failure(InventorySnapshotError.RestoreFailed(t.message ?: "snapshot repo threw"))
        }

        // 3. Restore prior state. If no session existed, create one in Lobby
        //    so subsequent restores observe a stable baseline.
        val restoredSession = priorSession?.copy(state = priorState)
            ?: PlayerSession(playerId = playerId, state = SessionState.Lobby)
        sessionRepo.upsert(restoredSession)

        // 4. Publish ExitReady so the reward bridge + audit log can react.
        eventBus.publish(
            PlayerPortalReady.ExitReady(
                playerId = playerId,
                returnToLobby = priorState is SessionState.Lobby,
            ),
        )

        return Result.success(snapshot)
    }
}

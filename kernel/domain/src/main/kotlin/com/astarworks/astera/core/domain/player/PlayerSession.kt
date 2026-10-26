package com.astarworks.astera.core.domain.player

import com.astarworks.astera.core.domain.match.MatchId

/**
 * Per-player runtime state (Wave 5e).
 *
 * One [SessionState] per player at any given time. Transitions happen via
 * application use cases (e.g., `EnterSpectatorUseCase`, `MatchSpawnUseCase`,
 * `RestoreInventorySnapshotUseCase`). Persistence is not required — sessions
 * are reconstructed at `PlayerJoined` from `IPlayerInventorySnapshotRepository`
 * + lobby defaults.
 *
 * [SessionState.Loading] is the action-lock state during
 * `MatchSpawnUseCase` / `RestoreInventoryUseCase`. Wave 6b's
 * `BukkitEventAdapter` cancels `InventoryClickEvent` /
 * `PlayerPickupItemEvent` / `PlayerInteractEvent` while a player is in this
 * state (RTM issue #5).
 */
public sealed interface SessionState {
    public data object Lobby : SessionState
    public data object InMatch : SessionState
    public data object Spectator : SessionState
    public data object Dead : SessionState
    public data object Disconnected : SessionState
    public data object Loading : SessionState
}

public data class PlayerSession(
    val playerId: PlayerId,
    val state: SessionState,
    val currentMatch: MatchId? = null,
)

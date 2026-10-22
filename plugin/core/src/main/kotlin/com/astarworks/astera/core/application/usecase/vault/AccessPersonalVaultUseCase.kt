package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IVaultWindow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Single entry point for "the player wants their vault" — used by `/astera vault`
 * and by the ender chest block click so both behave identically.
 *
 * The vault is lobby-only: a player in a running match (countdown / active /
 * sudden death) is refused with [VaultError.BlockedInMatch].
 */
public class AccessPersonalVaultUseCase(
    private val openVault: OpenVaultUseCase,
    private val window: () -> IVaultWindow?,
    private val matches: () -> IMatchRepository?,
) {

    public fun open(playerId: PlayerId): Result<Unit, VaultError> {
        val match = matches()?.findMatchOf(playerId)
        if (match != null && match.phase in LIVE_PHASES) {
            return Result.Failure(VaultError.BlockedInMatch)
        }
        val vaultWindow = window() ?: return Result.Failure(VaultError.WindowUnavailable)
        return when (val loaded = openVault.openVault(playerId)) {
            is Result.Failure -> Result.Failure(loaded.error)
            is Result.Success ->
                if (vaultWindow.show(loaded.value)) Result.Success(Unit)
                else Result.Failure(VaultError.WindowUnavailable)
        }
    }

    private companion object {
        val LIVE_PHASES = setOf(MatchPhase.COUNTDOWN, MatchPhase.ACTIVE, MatchPhase.SUDDEN_DEATH)
    }
}

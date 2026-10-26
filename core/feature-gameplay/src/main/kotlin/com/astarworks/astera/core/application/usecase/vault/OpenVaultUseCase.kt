package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault

/**
 * Loads the player's personal vault (Wave 5e, RTM issue #3).
 *
 * Returns [PersonalVault.empty] when no row exists — opening the GUI for a
 * first-time player must succeed, so "not yet persisted" is not an error.
 * Reserved [Result] return so future failure modes (e.g. lock contention with
 * the cross-server reward bridge) can be added without breaking the API.
 */
public class OpenVaultUseCase(private val vaults: IPersonalVaultRepository) {

    public suspend fun openVault(playerId: PlayerId): Result<PersonalVault, VaultError> {
        val existing = vaults.find(playerId) ?: PersonalVault.empty(playerId)
        return Result.Success(existing)
    }
}

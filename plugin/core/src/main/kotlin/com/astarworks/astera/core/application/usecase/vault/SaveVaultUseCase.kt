package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.vault.PersonalVault

/**
 * Persists a [PersonalVault] aggregate (Wave 5e, RTM issue #3).
 *
 * Validates each [PersonalVault.VaultItem] up-front so a malformed deposit
 * surfaces as [VaultError.InvalidCount] rather than as an
 * `IllegalArgumentException` thrown out of the data class `init`. The first
 * invalid item short-circuits — the persistence call only happens when every
 * item passes.
 */
public class SaveVaultUseCase(private val vaults: IPersonalVaultRepository) {

    public fun save(vault: PersonalVault): Result<Unit, VaultError> {
        vault.items.forEach { item ->
            if (item.count < 1) {
                return Result.Failure(VaultError.InvalidCount(item.itemId, item.count))
            }
        }
        vaults.upsert(vault)
        return Result.Success(Unit)
    }
}

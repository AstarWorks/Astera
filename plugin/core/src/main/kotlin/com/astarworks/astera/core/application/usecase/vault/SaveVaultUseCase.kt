package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.vault.PersonalVault

/**
 * Persists a [PersonalVault] aggregate.
 *
 * Validates each [PersonalVault.VaultItem] up-front so a malformed deposit
 * surfaces as a [VaultError] value. The persistence call only happens when
 * every item passes.
 */
public class SaveVaultUseCase(private val vaults: IPersonalVaultRepository) {

    public fun save(vault: PersonalVault): Result<Unit, VaultError> {
        vault.items.forEach { item ->
            if (item.count < 1) {
                return Result.Failure(VaultError.InvalidCount(item.itemId, item.count))
            }
            if (item.slot !in 0 until PersonalVault.SLOT_COUNT) {
                return Result.Failure(VaultError.InvalidSlot(item.slot))
            }
        }
        vaults.upsert(vault)
        return Result.Success(Unit)
    }
}

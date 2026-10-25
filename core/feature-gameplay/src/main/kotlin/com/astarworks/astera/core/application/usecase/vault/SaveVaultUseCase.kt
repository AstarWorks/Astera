package com.astarworks.astera.core.application.usecase.vault

import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
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
            if (item.slot !in 0 until PersonalVault.CAPACITY) {
                return Result.Failure(VaultError.InvalidSlot(item.slot))
            }
        }
        vaults.upsert(vault)
        return Result.Success(Unit)
    }

    /**
     * Persist what the player left in one window [page] (page-local slots) without touching the other
     * pages, so closing page 2 can never erase page 1 (ADR-0211).
     */
    public fun savePage(
        playerId: PlayerId,
        page: Int,
        local: List<PersonalVault.VaultItem>,
    ): Result<Unit, VaultError> {
        if (page !in 0 until PersonalVault.MAX_PAGES) return Result.Failure(VaultError.InvalidSlot(page))
        local.forEach { item ->
            if (item.count < 1) return Result.Failure(VaultError.InvalidCount(item.itemId, item.count))
            if (item.slot !in 0 until PersonalVault.PAGE_SIZE) return Result.Failure(VaultError.InvalidSlot(item.slot))
        }
        val current = vaults.find(playerId) ?: PersonalVault.empty(playerId)
        vaults.upsert(current.withPage(page, local))
        return Result.Success(Unit)
    }
}

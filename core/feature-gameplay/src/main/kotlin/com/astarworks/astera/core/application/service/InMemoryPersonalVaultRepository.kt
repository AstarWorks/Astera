package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [IPersonalVaultRepository] (Wave 5e,
 * RTM issue #3).
 *
 * Used by tests + dev runs. Persistence's `PostgresPersonalVaultRepository`
 * overlays this at priority 100 in production.
 *
 * The stored [PersonalVault] is the caller's reference — [PersonalVault] is a
 * `data class` over an immutable [List], so concurrent readers see a
 * consistent snapshot without defensive copying.
 */
public class InMemoryPersonalVaultRepository : IPersonalVaultRepository {

    private val vaults: ConcurrentHashMap<PlayerId, PersonalVault> = ConcurrentHashMap()

    override suspend fun find(playerId: PlayerId): PersonalVault? = vaults[playerId]

    override suspend fun upsert(vault: PersonalVault) {
        vaults[vault.playerId] = vault
    }

    override suspend fun remove(playerId: PlayerId) {
        vaults.remove(playerId)
    }
}

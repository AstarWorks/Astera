package com.astarworks.astera.core.platform.wiring

import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.vault.PersonalVault

/**
 * Repositories that resolve the highest-priority registration on every call.
 *
 * Core enables before Persistence, so a repository captured during Core's
 * wiring would pin the priority-0 in-memory default forever and the Postgres
 * overlay (priority 100, registered afterwards) would never see traffic.
 * Delegating through the registry keeps the priority-override pattern intact.
 */
internal class LiveVaultRepository(private val services: ServiceRegistry) : IPersonalVaultRepository {
    private fun delegate(): IPersonalVaultRepository = services.getOrThrow()

    override fun find(playerId: PlayerId): PersonalVault? = delegate().find(playerId)
    override fun upsert(vault: PersonalVault): Unit = delegate().upsert(vault)
    override fun remove(playerId: PlayerId): Unit = delegate().remove(playerId)
}

/** See [LiveVaultRepository]. */
internal class LiveSnapshotRepository(
    private val services: ServiceRegistry,
) : IPlayerInventorySnapshotRepository {
    private fun delegate(): IPlayerInventorySnapshotRepository = services.getOrThrow()

    override fun save(snapshot: PlayerInventorySnapshot): Unit = delegate().save(snapshot)
    override fun findLatest(playerId: PlayerId): PlayerInventorySnapshot? = delegate().findLatest(playerId)
    override fun delete(playerId: PlayerId): Unit = delegate().delete(playerId)
}

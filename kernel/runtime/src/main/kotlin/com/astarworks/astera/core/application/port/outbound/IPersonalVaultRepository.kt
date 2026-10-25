package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault

/**
 * Outbound port: per-player personal vault (Wave 5e, RTM issue #3).
 *
 * Backed by V7 `personal_vaults` (player_id PK, items JSONB) in
 * persistence; InMemory default in core for tests + dev.
 */
public interface IPersonalVaultRepository {
    public fun find(playerId: PlayerId): PersonalVault?
    public fun upsert(vault: PersonalVault)
    public fun remove(playerId: PlayerId)
}

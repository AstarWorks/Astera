package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import kotlinx.serialization.Serializable

/**
 * Wraps `List<PersonalVault.VaultItem>` so the JSONB column has a top-level
 * serializer (Wave 5e, RTM issue #3). Domain
 * [PersonalVault.VaultItem] stays free of `@Serializable`; this DTO mirrors
 * its shape for persistence and converts both ways.
 *
 * Order is preserved across the round-trip; each item carries its chest slot.
 */
@Serializable
public data class PersonalVaultJson(val items: List<VaultItemJson>) {

    public fun toDomain(playerId: PlayerId): PersonalVault =
        PersonalVault(
            playerId = playerId,
            items = items.map { it.toDomain() },
        )

    public companion object {
        public fun fromDomain(vault: PersonalVault): PersonalVaultJson =
            PersonalVaultJson(items = vault.items.map { VaultItemJson.fromDomain(it) })
    }
}

@Serializable
public data class VaultItemJson(
    val slot: Int,
    val itemId: String,
    val count: Int,
    val state: StoredItemStateJson = StoredItemStateJson(),
) {
    public fun toDomain(): PersonalVault.VaultItem =
        PersonalVault.VaultItem(slot = slot, itemId = itemId, count = count, state = state.toDomain())

    public companion object {
        public fun fromDomain(item: PersonalVault.VaultItem): VaultItemJson =
            VaultItemJson(slot = item.slot, itemId = item.itemId, count = item.count, state = StoredItemStateJson.fromDomain(item.state))
    }
}

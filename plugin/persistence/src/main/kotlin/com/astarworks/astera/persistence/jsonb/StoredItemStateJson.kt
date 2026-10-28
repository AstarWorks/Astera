package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.item.StoredItemState
import kotlinx.serialization.Serializable

/** JSONB mirror of [StoredItemState] (ADR-0302), shared by the inventory snapshot and the personal vault. */
@Serializable
public data class StoredItemStateJson(
    val damage: Int = 0,
    val enchants: Map<String, Int> = emptyMap(),
) {
    public fun toDomain(): StoredItemState = StoredItemState(damage, enchants)

    public companion object {
        public fun fromDomain(state: StoredItemState): StoredItemStateJson = StoredItemStateJson(state.damage, state.enchants)
    }
}

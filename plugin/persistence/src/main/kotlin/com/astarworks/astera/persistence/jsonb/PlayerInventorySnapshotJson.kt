package com.astarworks.astera.persistence.jsonb

import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wraps [PlayerInventorySnapshot]'s four canonical regions for the V10
 * `player_inventory_snapshots.snapshot` JSONB column (Wave 5e / RTM issue #5).
 *
 * Domain types ([PlayerInventorySnapshot] / [InventoryItemSlot]) stay free of
 * `@Serializable`; this DTO mirrors them with sealed-by-key discriminators
 * (`type: content | bukkit`) — same pattern as [ControlSchemeJson] /
 * [ParticleVisibilityJson] in [PlayerSettingsJson]. The `version` /
 * `savedAt` columns live on the table, not in the JSONB blob, so they can be
 * indexed without parsing the document.
 *
 * Per-region lists are encoded as JSON arrays of `Slot? = null | {type, …}`.
 * Sizes are revalidated by the [PlayerInventorySnapshot] `init` block on
 * `toDomain`, so a corrupt row (wrong-length array) fails fast at read time
 * with a clear `IllegalArgumentException` rather than silently mis-mapping
 * slots.
 */
@Serializable
public data class PlayerInventorySnapshotJson(
    val hotbar: List<InventoryItemSlotJson?>,
    val main: List<InventoryItemSlotJson?>,
    val offhand: InventoryItemSlotJson?,
    val armor: List<InventoryItemSlotJson?>,
) {

    public fun toDomain(
        playerId: PlayerId,
        version: Int,
        savedAt: java.time.Instant,
    ): PlayerInventorySnapshot = PlayerInventorySnapshot(
        playerId = playerId,
        hotbar = hotbar.map { it?.toDomain() },
        main = main.map { it?.toDomain() },
        offhand = offhand?.toDomain(),
        armor = armor.map { it?.toDomain() },
        version = version,
        savedAt = savedAt,
    )

    public companion object {
        public fun fromDomain(snapshot: PlayerInventorySnapshot): PlayerInventorySnapshotJson =
            PlayerInventorySnapshotJson(
                hotbar = snapshot.hotbar.map { it?.let { s -> InventoryItemSlotJson.fromDomain(s) } },
                main = snapshot.main.map { it?.let { s -> InventoryItemSlotJson.fromDomain(s) } },
                offhand = snapshot.offhand?.let { InventoryItemSlotJson.fromDomain(it) },
                armor = snapshot.armor.map { it?.let { s -> InventoryItemSlotJson.fromDomain(s) } },
            )
    }
}

/**
 * Sealed-by-key encoding for [InventoryItemSlot]: `{"type":"content","itemId":…}`
 * or `{"type":"bukkit","serializedItemStack":"<base64>"}`. Adding a new variant
 * means: add a domain `data class`, add a matching DTO branch with
 * `@SerialName`, add `toDomain` / `fromDomain` arms. An unknown discriminator
 * is fatal at decode time — force the deploy to ship matching code.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("type")
@Serializable
public sealed class InventoryItemSlotJson {

    public abstract fun toDomain(): InventoryItemSlot

    @Serializable
    @SerialName("content")
    public data class ContentItem(val itemId: String, val count: Int) : InventoryItemSlotJson() {
        override fun toDomain(): InventoryItemSlot = InventoryItemSlot.ContentItem(itemId = itemId, count = count)
    }

    @Serializable
    @SerialName("bukkit")
    public data class SerializedBukkit(val serializedItemStack: String) : InventoryItemSlotJson() {
        override fun toDomain(): InventoryItemSlot = InventoryItemSlot.SerializedBukkit(serializedItemStack = serializedItemStack)
    }

    public companion object {
        public fun fromDomain(slot: InventoryItemSlot): InventoryItemSlotJson = when (slot) {
            is InventoryItemSlot.ContentItem -> ContentItem(itemId = slot.itemId, count = slot.count)
            is InventoryItemSlot.SerializedBukkit -> SerializedBukkit(serializedItemStack = slot.serializedItemStack)
        }
    }
}

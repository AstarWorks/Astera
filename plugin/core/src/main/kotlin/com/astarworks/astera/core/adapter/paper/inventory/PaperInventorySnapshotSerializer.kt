package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.adapter.paper.item.PaperStoredItemCodec
import com.astarworks.astera.core.adapter.paper.lobby.MenuKitTag
import com.astarworks.astera.core.adapter.paper.lobby.TrainingItemTag
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Bukkit `PlayerInventory` <-> Astera [PlayerInventorySnapshot] codec (ADR-0158, id-based since ADR-0302).
 *
 * The saved form is an item **id** per slot (canonical regions: hotbar [9] / main [27] / offhand [1] /
 * armor [4]): [InventoryItemSlot.ContentItem] for Astera items, [InventoryItemSlot.VanillaItem] for
 * everything else, plus the small per-instance state. No ItemStack bytes are stored, so every restore
 * rebuilds each Astera stack from the current `content/items` definition through [PaperStoredItemCodec]
 * and the stacks are always up to date (name, lore, tooltip style, models).
 *
 * # Threading
 *
 * `Player.getInventory()` + `inventory.setContents` require the Bukkit main thread; callers run on the
 * entity owner thread.
 *
 * # Error isolation
 *
 * Per-slot failures log WARN and the slot is dropped (null) rather than failing the whole player.
 * Lobby menu kit items (ADR-0185) and training-rack weapons (ADR-0200) are never part of a snapshot.
 *
 * @see PlayerInventoryLifecycleListener for the join/quit driver.
 * @see InventoryActionLockEnforcer for the action-lock that fires while
 *      [com.astarworks.astera.core.domain.player.SessionState.Loading].
 */
public class PaperInventorySnapshotSerializer(
    private val codec: PaperStoredItemCodec,
    /** Runs after a snapshot was written into a live inventory (re-applies the lobby kit, ADR-0185). */
    private val onRestored: (Player) -> Unit = {},
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Snapshot [player]'s inventory into a vendor-neutral
     * [PlayerInventorySnapshot] (Wave 6b-3, ADR-0158).
     *
     * Reads the four canonical regions:
     *  - hotbar: slots 0..8 (`inventory.contents[0..8]`)
     *  - main:   slots 9..35 (`inventory.contents[9..35]`)
     *  - offhand: `inventory.itemInOffHand`
     *  - armor: `inventory.armorContents` (4 entries — boots / leggings /
     *           chest / helmet)
     *
     * Empty / air slots become `null`; every other stack becomes its item id
     * ([InventoryItemSlot.ContentItem] / [InventoryItemSlot.VanillaItem]).
     *
     * Per-slot serialization failures log WARN and are replaced with
     * `null` in the snapshot — one corrupt slot must not crash the whole
     * `PlayerQuitEvent` handler.
     */
    public fun serialize(player: Player): PlayerInventorySnapshot {
        val inv = player.inventory
        val contents = inv.contents // length = 36 (0..8 hotbar, 9..35 main)
        val armor = inv.armorContents // length = 4
        val offhand = inv.itemInOffHand

        val hotbar = (0 until HOTBAR_SIZE).map { idx ->
            encodeSlot(contents.getOrNull(idx), "hotbar[$idx]")
        }
        val main = (0 until MAIN_SIZE).map { idx ->
            val absoluteIdx = HOTBAR_SIZE + idx
            encodeSlot(contents.getOrNull(absoluteIdx), "main[$idx]")
        }
        val armorSlots = (0 until ARMOR_SIZE).map { idx ->
            encodeSlot(armor.getOrNull(idx), "armor[$idx]")
        }
        val offhandSlot = encodeSlot(offhand, "offhand")

        return PlayerInventorySnapshot(
            playerId = PlayerId(player.uniqueId),
            hotbar = hotbar,
            main = main,
            offhand = offhandSlot,
            armor = armorSlots,
            version = PlayerInventorySnapshot.CURRENT_VERSION,
            savedAt = Instant.now(),
        )
    }

    /**
     * Lower [snapshot] back into [player]'s live Bukkit inventory (Wave
     * 6b-3, ADR-0158).
     *
     * Reverses [serialize]: the four region lists are rebuilt as
     * `ItemStack` arrays and written via `setContents` / `setArmorContents`
     * / `setItemInOffHand`. Null entries become empty (air) stacks.
     *
     * Per-slot deserialization failures log WARN and leave the slot empty
     * — one corrupt slot must not crash the `PlayerJoinEvent` handler.
     * The caller is responsible for transitioning the player into and out
     * of [com.astarworks.astera.core.domain.player.SessionState.Loading]
     * so [InventoryActionLockEnforcer] suppresses click / pickup / interact
     * during the restore window.
     */
    public fun deserialize(snapshot: PlayerInventorySnapshot, player: Player) {
        val inv = player.inventory

        val combined = arrayOfNulls<ItemStack>(HOTBAR_SIZE + MAIN_SIZE)
        snapshot.hotbar.forEachIndexed { idx, slot ->
            combined[idx] = decodeSlot(slot, "hotbar[$idx]", snapshot.playerId)
        }
        snapshot.main.forEachIndexed { idx, slot ->
            combined[HOTBAR_SIZE + idx] = decodeSlot(slot, "main[$idx]", snapshot.playerId)
        }
        inv.contents = combined

        val armor = arrayOfNulls<ItemStack>(ARMOR_SIZE)
        snapshot.armor.forEachIndexed { idx, slot ->
            armor[idx] = decodeSlot(slot, "armor[$idx]", snapshot.playerId)
        }
        inv.armorContents = armor

        val offhand = decodeSlot(snapshot.offhand, "offhand", snapshot.playerId)
        inv.setItemInOffHand(offhand ?: ItemStack.empty())
        onRestored(player)
    }

    /** Encode a single `ItemStack?` to its id-based slot; empty, kit and training stacks return `null`. */
    private fun encodeSlot(item: ItemStack?, where: String): InventoryItemSlot? {
        if (item == null || item.isEmpty || MenuKitTag.isKit(item) || TrainingItemTag.isTraining(item)) return null
        return try {
            val stored = codec.encode(item) ?: return null
            if (stored.vanilla) {
                InventoryItemSlot.VanillaItem(stored.itemId, stored.count, stored.state)
            } else {
                InventoryItemSlot.ContentItem(stored.itemId, stored.count, stored.state)
            }
        } catch (t: Throwable) {
            log.warn("PaperInventorySnapshotSerializer.encodeSlot: {} failed; storing null", where, t)
            null
        }
    }

    /** Rebuild the stack of [slot] from the current item definition; a missing definition or failure gives `null`. */
    private fun decodeSlot(slot: InventoryItemSlot?, where: String, owner: PlayerId): ItemStack? {
        if (slot == null) return null
        val stored = when (slot) {
            is InventoryItemSlot.ContentItem -> PaperStoredItemCodec.Stored(slot.itemId, false, slot.count, slot.state)
            is InventoryItemSlot.VanillaItem -> PaperStoredItemCodec.Stored(slot.materialKey, true, slot.count, slot.state)
        }
        return try {
            codec.decode(stored, owner, where)
        } catch (t: Throwable) {
            log.warn("PaperInventorySnapshotSerializer.decodeSlot: {} failed; restoring empty", where, t)
            null
        }
    }

    public companion object {
        /** Bukkit hotbar size — `inventory.contents[0..8]`. */
        public const val HOTBAR_SIZE: Int = 9

        /** Bukkit main-inventory size — `inventory.contents[9..35]`. */
        public const val MAIN_SIZE: Int = 27

        /**
         * Bukkit armor-slot count — `inventory.armorContents` is fixed at
         * 4 (boots / leggings / chest / helmet).
         */
        public const val ARMOR_SIZE: Int = 4
    }
}

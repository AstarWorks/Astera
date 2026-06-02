package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.application.port.outbound.item.IContentItemRegistryLookup
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64

/**
 * Bukkit `PlayerInventory` ↔ Astera [PlayerInventorySnapshot] codec
 * (Wave 6b-3, ADR-0158).
 *
 * # Why this exists
 *
 * The Wave 5e domain ([PlayerInventorySnapshot]) holds opaque
 * [InventoryItemSlot] entries per canonical region (hotbar [9] / main [27]
 * / offhand [1] / armor [4]). The Wave 5e listener
 * (`match.application.eventbus.listeners.SaveInventoryOnMatchEndedListener`)
 * could only emit [PlayerInventorySnapshot.empty] placeholders because no
 * Paper-facing adapter existed yet. This codec is the lift/lower the Wave
 * 5e KDoc promised — concrete enough to round-trip vanilla ItemStacks and
 * any third-party NBT (Oraxen / WeaponMechanics) via the Bukkit-blessed
 * serialization stream.
 *
 * # Per-slot serialization (Base64 wrap)
 *
 * Each non-null `ItemStack` is written through a `BukkitObjectOutputStream`
 * which is the contract Paper exposes for round-tripping arbitrary NBT and
 * custom Bukkit-serializable item state. The resulting byte array is
 * Base64-wrapped and stored as
 * [InventoryItemSlot.SerializedBukkit.serializedItemStack]; deserialize
 * walks the inverse path. Empty / air slots are encoded as `null` in the
 * domain list (matching the Wave 5e shape; the domain `init` block
 * enforces fixed-size lists with nullable entries).
 *
 * The [InventoryItemSlot.ContentItem] variant (vendor-neutral itemId +
 * count) is **not** produced by this codec. ContentItem is the
 * Studio-authored path used by tests + admin reset; round-tripping vanilla
 * Bukkit state through it would lose NBT, so the Paper codec consistently
 * uses [InventoryItemSlot.SerializedBukkit]. Restore handles both variants:
 * Wave 6c §C (ADR-0160) wired [IContentItemRegistryLookup] so the decode
 * path resolves a content-pack id → material key → vanilla `ItemStack`
 * with the right base material + count. Custom NBT / display-name /
 * attribute restoration on the ContentItem branch is left for a follow-up
 * wave — slot ends up the correct material, not lost.
 *
 * # Threading
 *
 * `Player.getInventory()` + `inventory.setContents` require the Bukkit
 * main thread. Callers (Wave 6b's [PlayerInventoryLifecycleListener]) run
 * on the main thread via the `PlayerJoinEvent` / `PlayerQuitEvent`
 * dispatch loop, so no extra `scheduler.runLater` is needed. Off-main
 * usage will fail at the Bukkit boundary with a runtime check.
 *
 * # Error isolation
 *
 * Per-slot serialization is wrapped in try/catch — one corrupt slot logs
 * WARN and is dropped (null in the snapshot) rather than failing the
 * whole player. Same posture as the BukkitEventAdapter `safePublish`
 * pattern (ADR-0152 §A): bukkit listener handlers must never propagate
 * exceptions back into Paper's dispatch loop or the plugin disables.
 *
 * @see PlayerInventoryLifecycleListener for the join/quit driver.
 * @see InventoryActionLockEnforcer for the action-lock that fires while
 *      [com.astarworks.astera.core.domain.player.SessionState.Loading].
 */
public class PaperInventorySnapshotSerializer(
    private val itemLookup: IContentItemRegistryLookup? = null,
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
     * Empty / air slots become `null`; non-null `ItemStack`s are encoded
     * via [InventoryItemSlot.SerializedBukkit] (Base64-wrapped
     * `BukkitObjectOutputStream` output).
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
            combined[idx] = decodeSlot(slot, "hotbar[$idx]")
        }
        snapshot.main.forEachIndexed { idx, slot ->
            combined[HOTBAR_SIZE + idx] = decodeSlot(slot, "main[$idx]")
        }
        inv.contents = combined

        val armor = arrayOfNulls<ItemStack>(ARMOR_SIZE)
        snapshot.armor.forEachIndexed { idx, slot ->
            armor[idx] = decodeSlot(slot, "armor[$idx]")
        }
        inv.armorContents = armor

        val offhand = decodeSlot(snapshot.offhand, "offhand")
        inv.setItemInOffHand(offhand ?: ItemStack.empty())
    }

    /**
     * Encode a single `ItemStack?` to [InventoryItemSlot.SerializedBukkit].
     * Empty / null / air stacks return `null`. Serialization failures log
     * WARN and return `null` so the snapshot stays well-shaped.
     */
    private fun encodeSlot(item: ItemStack?, where: String): InventoryItemSlot? {
        if (item == null || item.isEmpty) return null
        return try {
            val baos = ByteArrayOutputStream()
            BukkitObjectOutputStream(baos).use { it.writeObject(item) }
            val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())
            InventoryItemSlot.SerializedBukkit(serializedItemStack = base64)
        } catch (t: Throwable) {
            log.warn(
                "PaperInventorySnapshotSerializer.encodeSlot: {} failed; storing null",
                where,
                t,
            )
            null
        }
    }

    /**
     * Decode an [InventoryItemSlot] back into a Bukkit `ItemStack?`. Null
     * input → null output. ContentItem (vendor-neutral itemId path) is
     * not yet resolvable to a Bukkit material at this layer (no item-id
     * registry in Core); WARN + null until a future wave wires one. Per-
     * slot failures log WARN + null so one bad row does not crash the
     * restore.
     */
    private fun decodeSlot(slot: InventoryItemSlot?, where: String): ItemStack? {
        if (slot == null) return null
        return when (slot) {
            is InventoryItemSlot.SerializedBukkit -> {
                try {
                    val bytes = Base64.getDecoder().decode(slot.serializedItemStack)
                    BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { ois ->
                        ois.readObject() as? ItemStack
                    }
                } catch (t: Throwable) {
                    log.warn(
                        "PaperInventorySnapshotSerializer.decodeSlot: {} (SerializedBukkit) failed; restoring empty",
                        where,
                        t,
                    )
                    null
                }
            }
            is InventoryItemSlot.ContentItem -> {
                val lookup = itemLookup
                if (lookup == null) {
                    log.warn(
                        "PaperInventorySnapshotSerializer.decodeSlot: {} carries ContentItem(id={}, count={}) but no IContentItemRegistryLookup wired; slot restored as empty",
                        where,
                        slot.itemId,
                        slot.count,
                    )
                    return null
                }
                val materialKey = lookup.bukkitMaterialKeyFor(slot.itemId)
                    ?: run {
                        log.warn(
                            "PaperInventorySnapshotSerializer.decodeSlot: {} carries ContentItem(id={}, count={}) — registry resolved no material; slot restored as empty",
                            where,
                            slot.itemId,
                            slot.count,
                        )
                        return null
                    }
                val material = Material.matchMaterial(materialKey)
                    ?: run {
                        log.warn(
                            "PaperInventorySnapshotSerializer.decodeSlot: {} resolved id={} -> materialKey={} which Material.matchMaterial returned null; slot restored as empty",
                            where,
                            slot.itemId,
                            materialKey,
                        )
                        return null
                    }
                ItemStack(material, slot.count.coerceAtLeast(1))
            }
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

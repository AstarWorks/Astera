package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiVisibility
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.proxy.ServerRole

/** Where on the player a [KitEntry] is shown (ADR-0185). */
public enum class KitArea(public val slotCount: Int) {
    /** Hotbar slots 0..8, used with right-click. */
    HOTBAR(9),

    /** The 2x2 crafting input of the player's own inventory: grid index 0..3 (row-major). */
    CRAFTING_GRID(4),

    /** The single crafting result slot of the player's own inventory. */
    CRAFTING_RESULT(1),

    /** Swap-hand key (F) pressed while not holding a weapon (ADR-0232). Slot is always 0; no item is given. */
    KEY_SWAP(1),

    /** Sneak + swap-hand key (sneak+F) while not holding a weapon (ADR-0232). */
    KEY_SNEAK_SWAP(1),

    /** The drop key (Q) pressed on the hotbar menu item (ADR-0232). */
    KEY_DROP(1),
    ;

    /** True for the keybind areas: they dispatch an intent but occupy no inventory slot. */
    public val isKeybind: Boolean get() = this == KEY_SWAP || this == KEY_SNEAK_SWAP || this == KEY_DROP
}

/**
 * One locked menu item: an icon ([itemId] from the item registry) in a fixed
 * [area]/[slot] that dispatches [intent] when the player uses it.
 */
public data class KitEntry(
    public val id: String,
    public val area: KitArea,
    public val slot: Int,
    public val itemId: ItemId,
    public val intent: GuiIntent,
    public val loreKey: MessageKey? = null,
    /** Per-viewer condition ([GuiVisibility], ADR-0240). When satisfied, this entry replaces any unconditional entry at the same place. */
    public val visibleIf: String? = null,
    /** Extra lore lines appended after [loreKey], each with its own optional condition (e.g. an ops-only hint). */
    public val extraLore: List<KitLore> = emptyList(),
) {
    init {
        GuiVisibility.validate(visibleIf)
        require(id.isNotBlank()) { "KitEntry.id must not be blank" }
        require(slot in 0 until area.slotCount) { "KitEntry '$id': slot $slot is outside $area (0..${area.slotCount - 1})" }
    }

    /** The [extraLore] keys this viewer sees (conditions evaluated by [hasPermission]). */
    public fun extraLoreFor(hasPermission: (node: String) -> Boolean): List<MessageKey> =
        extraLore.filter { GuiVisibility.isVisible(it.visibleIf, hasPermission) }.map { it.key }
}

/** One extra lore line of a [KitEntry]; shown when [visibleIf] (or always, when `null`) holds for the viewer. */
public data class KitLore(
    public val key: MessageKey,
    public val visibleIf: String? = null,
) {
    init {
        GuiVisibility.validate(visibleIf)
    }
}

/** The validated menu-kit layout loaded from `content/gui/kit/lobby-kit.yaml`. */
public class LobbyMenuKit(entries: List<KitEntry>) {

    public val entries: List<KitEntry> = entries.toList()

    private val byId: Map<String, KitEntry>

    init {
        val ids = HashSet<String>()
        val places = HashSet<Triple<KitArea, Int, String?>>()
        for (e in this.entries) {
            require(ids.add(e.id)) { "duplicate kit entry id '${e.id}'" }
            require(e.area != KitArea.HOTBAR || e.slot in HotbarLayout.KIT_SLOTS) {
                "kit entry '${e.id}': hotbar slot ${e.slot} is in the weapon zone; kit slots are ${HotbarLayout.KIT_SLOTS}"
            }
            require(e.area != KitArea.HOTBAR || this.entries.count { it.area == KitArea.HOTBAR } <= HotbarLayout.MAX_KIT_ITEMS) {
                "kit has more than ${HotbarLayout.MAX_KIT_ITEMS} hotbar item (ADR-0232: the hotbar belongs to the player)"
            }
            require(places.add(Triple(e.area, e.slot, e.visibleIf))) { "kit slot ${e.area}/${e.slot} is used twice" }
        }
        byId = this.entries.associateBy { it.id }
    }

    public fun find(id: String): KitEntry? = byId[id]

    /**
     * The kit as one viewer sees it (ADR-0240): entries whose `visibleIf` fails are dropped, and a
     * visible conditional entry replaces the unconditional entry at the same area/slot. The result
     * has no conditions left, so the usual accessors apply.
     */
    public fun forViewer(hasPermission: (node: String) -> Boolean): LobbyMenuKit {
        val visible = entries.filter { GuiVisibility.isVisible(it.visibleIf, hasPermission) }
        val overridden = visible.filter { it.visibleIf != null }.map { it.area to it.slot }.toSet()
        return LobbyMenuKit(
            visible.filter { it.visibleIf != null || (it.area to it.slot) !in overridden }
                .map { it.copy(visibleIf = null) },
        )
    }

    /** Whether any entry is viewer-conditional. */
    public val hasConditionalEntries: Boolean get() = entries.any { it.visibleIf != null }

    public fun entries(area: KitArea): List<KitEntry> = entries.filter { it.area == area }

    public fun at(area: KitArea, slot: Int): KitEntry? = entries.firstOrNull { it.area == area && it.slot == slot }

    /** True when crafting-grid buttons exist (the inventory screen then needs click locking). */
    public val hasCraftingButtons: Boolean get() = entries.any { it.area == KitArea.CRAFTING_GRID || it.area == KitArea.CRAFTING_RESULT }
}

/**
 * Which keybind area a swap-hand press triggers (ADR-0232). Weapons bind LC / RC / sneak+click / Q (drop) /
 * shoot, never the swap-hand key, but a weapon in hand keeps vanilla F so the hand-swap stays usable there.
 */
public object LobbyKeybinds {
    public fun swapArea(holdingWeapon: Boolean, sneaking: Boolean): KitArea? = when {
        holdingWeapon -> null
        sneaking -> KitArea.KEY_SNEAK_SWAP
        else -> KitArea.KEY_SWAP
    }
}

/** When a player carries the kit at all. */
public object LobbyMenuKitPolicy {

    /**
     * The kit belongs to the lobby: shown on backends that host it and never while the
     * player is inside a match (on an `ALL` backend the same player can be in either).
     */
    public fun active(role: ServerRole, inMatch: Boolean): Boolean = role.hostsLobby && !inMatch

    /**
     * Whether a player counts as "in a match" for the kit (F3-10). Members are added to a match
     * *after* `MatchStarted` (queue formation / `entry`), so the inventory-snapshot hold alone
     * misses them; membership in a not-yet-ended match is the authoritative signal.
     */
    public fun inMatch(snapshotHeld: Boolean, membershipPhase: MatchPhase?): Boolean =
        snapshotHeld || (membershipPhase != null && membershipPhase != MatchPhase.ENDED)
}

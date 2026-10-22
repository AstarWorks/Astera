package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.domain.gui.GuiIntent
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
) {
    init {
        require(id.isNotBlank()) { "KitEntry.id must not be blank" }
        require(slot in 0 until area.slotCount) { "KitEntry '$id': slot $slot is outside $area (0..${area.slotCount - 1})" }
    }
}

/** The validated menu-kit layout loaded from `content/gui/kit/lobby-kit.yaml`. */
public class LobbyMenuKit(entries: List<KitEntry>) {

    public val entries: List<KitEntry> = entries.toList()

    private val byId: Map<String, KitEntry>

    init {
        val ids = HashSet<String>()
        val places = HashSet<Pair<KitArea, Int>>()
        for (e in this.entries) {
            require(ids.add(e.id)) { "duplicate kit entry id '${e.id}'" }
            require(places.add(e.area to e.slot)) { "kit slot ${e.area}/${e.slot} is used twice" }
        }
        byId = this.entries.associateBy { it.id }
    }

    public fun find(id: String): KitEntry? = byId[id]

    public fun entries(area: KitArea): List<KitEntry> = entries.filter { it.area == area }

    public fun at(area: KitArea, slot: Int): KitEntry? = entries.firstOrNull { it.area == area && it.slot == slot }

    /** True when crafting-grid buttons exist (the inventory screen then needs click locking). */
    public val hasCraftingButtons: Boolean get() = entries.any { it.area != KitArea.HOTBAR }
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

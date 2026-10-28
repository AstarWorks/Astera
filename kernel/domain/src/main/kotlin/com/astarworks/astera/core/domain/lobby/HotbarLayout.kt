package com.astarworks.astera.core.domain.lobby

/**
 * Who owns which hotbar slot (ADR-0211, tightened by ADR-0232).
 *
 * - **Lobby (incl. training range)**: at most ONE locked menu item (the compass) sits in [KIT_SLOTS]
 *   (slot 8). Weapons - starter grant, shop, training rack, admin give - take [WEAPON_SLOTS]
 *   left to right. Everything else is reached from the menu, the crafting-grid buttons and keybinds.
 * - **Match**: no menu kit. A mode that hands out the navigation compass (Annihilation, [MATCH_COMPASS_SLOT]) reserves
 *   slot 8 for it; weapons take [WEAPON_SLOTS] like in the lobby. Modes without a compass leave every slot free.
 *
 * Bukkit's `addItem` already fills the hotbar from slot 0, so delivery needs no special case as long as
 * the kit stays out of the weapon zone; [LobbyMenuKit] enforces that at load time.
 */
public object HotbarLayout {
    public const val KIT_FIRST_SLOT: Int = 8
    public const val HOTBAR_SIZE: Int = 9

    /** The kit may occupy at most this many hotbar slots (ADR-0232). */
    public const val MAX_KIT_ITEMS: Int = 1

    /** The match navigation compass (locked item, shown only while held) always sits here. */
    public const val MATCH_COMPASS_SLOT: Int = 8

    public val KIT_SLOTS: IntRange = KIT_FIRST_SLOT until HOTBAR_SIZE
    public val WEAPON_SLOTS: IntRange = 0 until KIT_FIRST_SLOT

    /** The first hotbar slot a freshly given weapon should take, or `null` when the hotbar is full. */
    public fun firstFreeSlot(occupied: Set<Int>, kitActive: Boolean, matchCompass: Boolean = false): Int? {
        val order = if (kitActive || matchCompass) WEAPON_SLOTS else 0 until HOTBAR_SIZE
        return order.firstOrNull { it !in occupied }
    }
}

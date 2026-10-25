package com.astarworks.astera.core.application.service.lobby

/**
 * What an inventory click touches, reduced to the facts the lock rules need
 * (ADR-0185). The Paper listener fills this in; keeping the decision here makes the
 * exploit-proofing testable without a server.
 *
 * @property clickedIsKit the stack under the pointer is a kit item
 * @property cursorIsKit the stack on the cursor is a kit item
 * @property swapSourceIsKit number-key / offhand swap would move a kit item (hotbar button or offhand)
 * @property inCraftingZone the click targets the player's own crafting result/input slots
 * @property buttonsShown the crafting grid currently shows kit buttons
 * @property collectToCursor double-click "gather all matching items" (scans every slot)
 * @property topIsOwnCrafting the open top inventory is the player's own 2x2 crafting screen
 */
public data class KitClickFacts(
    val clickedIsKit: Boolean = false,
    val cursorIsKit: Boolean = false,
    val swapSourceIsKit: Boolean = false,
    val inCraftingZone: Boolean = false,
    val buttonsShown: Boolean = false,
    val collectToCursor: Boolean = false,
    val topIsOwnCrafting: Boolean = false,
)

/** Pure decision rules for the locked lobby kit. */
public object LobbyKitLockRules {

    /** True when the click must be cancelled: no kit item may ever move, and the shown grid is read-only. */
    public fun cancelClick(f: KitClickFacts): Boolean =
        f.clickedIsKit ||
            f.cursorIsKit ||
            f.swapSourceIsKit ||
            (f.buttonsShown && f.inCraftingZone) ||
            (f.buttonsShown && f.collectToCursor && f.topIsOwnCrafting)

    /** A click that presses a crafting-grid button (only primary clicks on a kit item in the crafting zone). */
    public fun pressesButton(f: KitClickFacts, primaryClick: Boolean): Boolean =
        primaryClick && f.clickedIsKit && f.inCraftingZone && f.topIsOwnCrafting

    /**
     * True when a drag must be cancelled: it carries a kit item, spreads over the shown grid
     * or ends on a slot that currently holds a kit item.
     */
    public fun cancelDrag(
        cursorIsKit: Boolean,
        buttonsShown: Boolean,
        touchesCraftingZone: Boolean,
        touchesKitSlot: Boolean,
    ): Boolean = cursorIsKit || touchesKitSlot || (buttonsShown && touchesCraftingZone)
}

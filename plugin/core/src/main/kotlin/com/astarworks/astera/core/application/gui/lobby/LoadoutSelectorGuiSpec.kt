package com.astarworks.astera.core.application.gui.lobby

import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiSize
import com.astarworks.astera.core.domain.gui.GuiSlot
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Wave 5game-complete (ADR-0121 §B) — 9-slot loadout selector GUI.
 *
 * # User-facing surface
 *
 * Opened by the `loadout.selector.show` intent (currently emitted by
 * the lobby compass tile's existing customize-menu sub-tile via
 * `weapons.gui.loadout.open` aliasing during Wave 5e; the loadout
 * selector replaces the legacy weapons-plugin `:loadout` GUI). 9
 * horizontal slots, each carrying one saved loadout (slots 1-9).
 *
 * # Click semantics per slot
 *
 *  - **Left-click** — `loadout.selector.activate` with payload
 *    `{slot: <1..9>}`. The active loadout is updated to the chosen
 *    slot and the GUI closes.
 *  - **Right-click** — `loadout.selector.rename` with payload
 *    `{slot: <1..9>}`. Opens the rename anvil prompt (Wave 6 GUI).
 *  - **Shift-click** — `loadout.selector.reset` with payload
 *    `{slot: <1..9>}`. Clears the slot.
 *  - **Middle-click** — `loadout.selector.save` with payload
 *    `{slot: <1..9>, mode: <norma|annihilation|bet-1v1>}`. Saves the
 *    player's current Items inventory into the slot.
 *
 * The "save" intent payload includes a `mode` key so per-mode loadouts
 * are addressable from the same selector — Phase 1 PoC ships the
 * intent payload but the per-mode dispatch logic lives in the items
 * plugin's intent handler (currently a no-op outside Norma).
 *
 * # Why 9 slots fixed (not paginated)
 *
 * Phase 1 PoC: 9 slot ceiling matches RTM's loadout taxonomy and keeps
 * the GUI to a single CHEST_9 row. Phase 4 may paginate via a chest_18
 * with the bottom row holding nav arrows; this spec stays at 9 until
 * authoring demand justifies the complexity.
 *
 * # Slot item rendering
 *
 * Each slot renders the placeholder item id `gui-tile-loadout` until
 * the items plugin's `IItemRegistry` is online and the loadout-aware
 * dynamic slot variant lands (Wave 6b). The renderer falls back to a
 * paper-icon if the item id is unknown, so a server without the items
 * plugin running still sees the layout.
 */
public object LoadoutSelectorGuiSpec {

    public val ID: GuiId = GuiId("loadout-selector")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.loadout-selector.title"),
        size = GuiSize.CHEST_9,
    ) {
        for (n in 1..SLOT_COUNT) {
            slot(
                SlotIndex(n - 1),
                GuiSlot.Static(
                    itemId = ItemId(SLOT_ITEM_ID),
                    onLeftClick = GuiIntent(
                        INTENT_ACTIVATE,
                        mapOf(PAYLOAD_SLOT to n.toString()),
                    ),
                    onRightClick = GuiIntent(
                        INTENT_RENAME,
                        mapOf(PAYLOAD_SLOT to n.toString()),
                    ),
                    onShiftClick = GuiIntent(
                        INTENT_RESET,
                        mapOf(PAYLOAD_SLOT to n.toString()),
                    ),
                    onMiddleClick = GuiIntent(
                        INTENT_SAVE,
                        mapOf(
                            PAYLOAD_SLOT to n.toString(),
                            PAYLOAD_MODE to DEFAULT_MODE_KEY,
                        ),
                    ),
                    tooltip = listOf(
                        MessageKey("astera.gui.loadout-selector.slot.tooltip.activate"),
                        MessageKey("astera.gui.loadout-selector.slot.tooltip.rename"),
                        MessageKey("astera.gui.loadout-selector.slot.tooltip.reset"),
                        MessageKey("astera.gui.loadout-selector.slot.tooltip.save"),
                    ),
                ),
            )
        }
    }

    /** 9 fixed loadout slots — matches RTM taxonomy. */
    public const val SLOT_COUNT: Int = 9

    /** Placeholder item id for every tile (renderer falls back to paper-icon when unknown). */
    public const val SLOT_ITEM_ID: String = "gui-tile-loadout"

    /** Default mode used when middle-click "save" is triggered without explicit mode payload. */
    public const val DEFAULT_MODE_KEY: String = "norma"

    /** Intent prefix shared by every per-slot click. */
    public const val INTENT_PREFIX: String = "loadout.selector."

    public const val INTENT_SHOW: String = "loadout.selector.show"
    public const val INTENT_ACTIVATE: String = "loadout.selector.activate"
    public const val INTENT_RENAME: String = "loadout.selector.rename"
    public const val INTENT_RESET: String = "loadout.selector.reset"
    public const val INTENT_SAVE: String = "loadout.selector.save"

    public const val PAYLOAD_SLOT: String = "slot"
    public const val PAYLOAD_MODE: String = "mode"
}

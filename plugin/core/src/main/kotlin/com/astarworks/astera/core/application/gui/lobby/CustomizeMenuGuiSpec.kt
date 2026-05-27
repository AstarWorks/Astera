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
 * Wave 5lobby-content (ADR-0119) — customize sub-menu.
 *
 * Opened by `lobby.customize.show` / `lobby.customize.open`. Three tiles in
 * a single 9-slot row:
 *
 *  - Slot 2: Loadout → emits `loadout.selector.show` (Wave 5game-complete
 *    [LoadoutSelectorGuiSpec]; legacy `weapons.gui.loadout.open` is gone
 *    with the items-plugin rename).
 *  - Slot 4: Refinement → emits `lobby.refinement.open` (Wave 5refine intent
 *    bridge — currently a placeholder until the items plugin lands the
 *    matching handler).
 *  - Slot 6: Profile → emits `lobby.profile.show` (handled by
 *    [com.astarworks.astera.core.application.gui.lobby.intent.LobbyProfileShowIntentHandler]).
 *
 * Each tile is a thin navigation shortcut — the heavy lifting lives in the
 * downstream handlers / specs.
 */
public object CustomizeMenuGuiSpec {

    public val ID: GuiId = GuiId("customize-menu")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.customize-menu.title"),
        size = GuiSize.CHEST_9,
    ) {
        slot(
            SlotIndex(2),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-customize"),
                // Wave 5game-complete (ADR-0121 §B): repoint loadout tile from
                // legacy weapons-plugin intent (`weapons.gui.loadout.open`) to
                // the new 9-slot LoadoutSelectorGuiSpec entry point. The
                // legacy intent is gone with the items-plugin rename and the
                // selector is now the canonical authoring surface.
                onLeftClick = GuiIntent(LoadoutSelectorGuiSpec.INTENT_SHOW),
                tooltip = listOf(MessageKey("astera.gui.customize-menu.loadout.tooltip")),
            ),
        )
        slot(
            SlotIndex(4),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-refinement"),
                onLeftClick = GuiIntent("lobby.refinement.open"),
                tooltip = listOf(MessageKey("astera.gui.customize-menu.refinement.tooltip")),
            ),
        )
        slot(
            SlotIndex(6),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-profile"),
                onLeftClick = GuiIntent("lobby.profile.show"),
                tooltip = listOf(MessageKey("astera.gui.customize-menu.profile.tooltip")),
            ),
        )
    }
}

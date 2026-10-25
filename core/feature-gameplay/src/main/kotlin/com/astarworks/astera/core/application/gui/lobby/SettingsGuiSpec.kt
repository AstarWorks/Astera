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
 * Wave 5lobby-content (ADR-0119) — settings (Phase 1 skeleton).
 *
 * Opened by `lobby.settings.show`. Settings application handlers
 * (`settings.locale.cycle` / `settings.volume.adjust` /
 * `settings.controls.cycle`) are scheduled for **Wave G**; this wave only
 * commits the GUI **surface** so the hotbar route is clickable today —
 * clicking a tile is a no-op until the Wave G handlers register.
 *
 * Layout (`CHEST_9`):
 *  - Slot 2: locale → `settings.locale.cycle`
 *  - Slot 4: volume → `settings.volume.adjust`
 *  - Slot 6: controls → `settings.controls.cycle`
 *  - Slot 8: close → `gui.close`
 */
public object SettingsGuiSpec {

    public val ID: GuiId = GuiId("settings")

    public val SPEC: GuiSpec = GuiSpec.build(
        id = ID,
        title = MessageKey("astera.gui.settings.title"),
        size = GuiSize.CHEST_9,
    ) {
        slot(
            SlotIndex(2),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-settings"),
                onLeftClick = GuiIntent("settings.locale.cycle"),
                tooltip = listOf(MessageKey("astera.gui.settings.locale.tooltip")),
            ),
        )
        slot(
            SlotIndex(4),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-settings"),
                onLeftClick = GuiIntent("settings.volume.adjust"),
                tooltip = listOf(MessageKey("astera.gui.settings.volume.tooltip")),
            ),
        )
        slot(
            SlotIndex(6),
            GuiSlot.Static(
                itemId = ItemId("gui-tile-settings"),
                onLeftClick = GuiIntent("settings.controls.cycle"),
                tooltip = listOf(MessageKey("astera.gui.settings.controls.tooltip")),
            ),
        )
        slot(
            SlotIndex(8),
            GuiSlot.Static(
                itemId = ItemId("gui-pane-red"),
                onLeftClick = GuiIntent("gui.close"),
                tooltip = listOf(MessageKey("astera.gui.button.close")),
            ),
        )
    }
}
